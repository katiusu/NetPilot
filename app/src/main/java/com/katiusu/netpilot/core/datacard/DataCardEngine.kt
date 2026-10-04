package com.katiusu.netpilot.core.datacard

import android.content.Context
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.core.monitor.SignalSnapshot
import com.katiusu.netpilot.core.priv.ControlManager

/**
 * 数据卡自动切换 + 单卡策略执行器。
 *
 * 和 TrafficSIM 的区别：它是 LSPosed 模块，靠 Hook `SubscriptionController`
 * 在框架内部改默认数据卡；这里是普通 APP，走
 * `settings put global multi_sim_data_call <subId>` —— 系统的 PhoneSwitcher
 * 观察这个 setting，写入后自己会把数据业务切过去。少一层 Xposed，代价是不能
 * 在「框架已经拒绝」的场景下强行改（比如运营商把数据卡钉死在卡 1）。
 *
 * 触发源就是监控引擎每次采样得到的 Wi-Fi SSID/BSSID，不额外注册
 * CONNECTIVITY_ACTION（Android 8 之后静态广播已失效，动态注册又要跟服务生命周期
 * 对齐，而循环本来就在采 Wi-Fi 信息，复用它最省事也最不容易漏触发）。
 *
 * ## 规则求值模型：规则自带目标卡
 *
 * 遍历规则库，取**第一条命中的规则**（规则库已按 `priority` 降序读出，同优先级保持
 * 写入顺序，所以这里是确定的），切卡目标就是这条规则自己的 `targetSubId`（-1 = 不切卡）。
 * 中间曾经有过一版「卡订阅规则」模型（由卡的 `ruleIds` 决定切到哪张卡），已按用户
 * 要求撤销 —— 那会让「这条规则到底是干嘛的」变得要看两张卡才知道。
 *
 * ## 单卡策略
 *
 * 每张卡的模式 → 实际启用哪几项策略，唯一收敛在 [effectiveStrategies]。
 * **网络质量降级的执行者不在这里**：它是 `core/monitor/AutoDowngradeEngine`
 * （带冷却、恢复计数、启动自愈的全局状态机，作用于当前默认数据卡）。本引擎再实现
 * 一套会在同一张卡上互相写制式，所以这里只把「这张卡要不要网络质量降级」算出来；
 * monitor 侧要接每卡门控时直接读 [effectiveStrategies] 即可。
 * 本引擎实际落地的是 **Wi-Fi 降级**（连上 Wi-Fi 降为 4G，断开或关闭策略后还原）。
 */
object DataCardEngine {

    private const val TAG = "数据卡规则"

    /** 同一个 subId 两次制式写入之间的最小间隔：防止在 Wi-Fi 边缘反复横跳。 */
    private const val MODE_WRITE_THROTTLE_MS = 60_000L

    /**
     * 当前生效的匹配：命中的规则 + 这次要切的卡。
     *
     * 必须把目标卡一起记下来 —— 否则「已经切过去了」的短路判断和离开 Wi-Fi
     * 时的回切都会判错。
     */
    private data class ActiveMatch(val ruleId: String, val targetSubId: Int)

    @Volatile private var activeMatch: ActiveMatch? = null

    @Volatile private var lastApplyAtMs = 0L

    /** 每个 subId 上次写制式的时间（Wi-Fi 降级用）。 */
    private val lastModeWriteAt = mutableMapOf<Int, Long>()

    /**
     * Wi-Fi 降级前的制式：subId → 制式裸值。
     *
     * 存下来才能「断开 Wi-Fi 后还原」。没有它就只能一直停在 4G（旧实现的缺陷）。
     */
    private val savedModeBeforeWifiDowngrade = mutableMapOf<Int, Int>()

    fun matchedRule(context: Context): DataCardRule? =
        activeMatch?.ruleId?.let { id -> DataCardStore.rules(context).firstOrNull { it.id == id } }

    fun reset() {
        activeMatch = null
        lastApplyAtMs = 0L
        lastModeWriteAt.clear()
        savedModeBeforeWifiDowngrade.clear()
    }

    /**
     * 这张卡当前实际生效的策略集合 —— 「模式 + 自定义开关 → 策略」的**唯一**映射处。
     *
     * 界面用它决定每行的明暗，执行侧用它决定做不做。不要在别处再写一遍这套判断，
     * 否则「界面上写着开着、实际没跑」这类不一致迟早会出现。
     */
    fun effectiveStrategies(policy: SimPolicy): Set<DataCardStrategy> = when (policy.mode) {
        SimPolicyMode.FOLLOW_SYSTEM -> emptySet()
        SimPolicyMode.NETWORK_QUALITY -> setOf(DataCardStrategy.NETWORK_QUALITY)
        SimPolicyMode.WIFI_DOWNGRADE -> setOf(DataCardStrategy.WIFI_DOWNGRADE)
        SimPolicyMode.CUSTOM -> {
            val set = mutableSetOf<DataCardStrategy>()
            if (policy.customNetworkQuality) set += DataCardStrategy.NETWORK_QUALITY
            if (policy.customWifiDowngrade) set += DataCardStrategy.WIFI_DOWNGRADE
            set
        }
    }

    /**
     * 单条规则是否匹配当前 Wi-Fi。
     * BSSID 精确 → SSID 相等（忽略大小写）→ 空 SSID 表示任意 Wi-Fi。
     */
    fun matches(rule: DataCardRule, ssid: String?, bssid: String?): Boolean {
        if (!rule.enabled) return false
        val s = ssid?.trim().orEmpty()
        val b = bssid?.trim().orEmpty()
        return when {
            rule.bssid.isNotBlank() -> rule.bssid.equals(b, ignoreCase = true)
            rule.ssid.isNotBlank() -> rule.ssid.equals(s, ignoreCase = true)
            else -> true
        }
    }

    /**
     * 每次采样调用一次。返回非 null 表示发生了需要记录的变化（调用方打日志）。
     */
    suspend fun onSample(context: Context, snap: SignalSnapshot): String? {
        val messages = mutableListOf<String>()
        runCatching { evaluateRules(context, snap) }
            .onSuccess { msg -> msg?.let { messages += it } }
            .onFailure { LogStore.warn(TAG, "规则执行异常：${it.message ?: it.javaClass.simpleName}") }
        runCatching { evaluatePolicies(context, snap) }
            .onSuccess { msgs -> messages += msgs }
            .onFailure { LogStore.warn(TAG, "单卡策略执行异常：${it.message ?: it.javaClass.simpleName}") }
        return messages.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    // ---------------- Wi-Fi 规则（规则自带目标卡） ----------------

    private suspend fun evaluateRules(context: Context, snap: SignalSnapshot): String? {
        if (!DataCardStore.rulesEnabled(context)) return null
        // 已按 priority 降序排好，同优先级保持稳定顺序 —— 求值顺序即优先级顺序。
        val rules = DataCardStore.rules(context)
        val rulesById = rules.associateBy { it.id }
        val current = SimReader.defaultDataSubId()

        // 不在 Wi-Fi 上：只看上一轮命中的规则要不要回切。回切目标是全局的 homeSubId，
        // 与规则本身无关，所以这里不能因为规则库为空就跳过。
        if (!snap.isWifi) return revertIfNeeded(context, rulesById, current)

        if (rules.isEmpty()) return null
        val policies = DataCardStore.policies(context)
        val rule = rules.firstOrNull { r ->
            matches(r, snap.wifiSsid, snap.wifiBssid) && targetCardAllows(policies, r.targetSubId)
        } ?: return null

        val targetSubId = rule.targetSubId
        val now = System.currentTimeMillis()
        val cooldownMs = rule.cooldownSec.coerceIn(10, 3600) * 1000L

        // 同一条规则 + 同一张目标卡，而且已经切到目标卡上了：什么都不用做。
        // 少了这个短路，每次采样（秒级）都会去写一遍 setting。
        val sameMatch = activeMatch?.let { it.ruleId == rule.id && it.targetSubId == targetSubId } == true
        if (sameMatch && (targetSubId < 0 || targetSubId == current)) return null
        if (now - lastApplyAtMs < cooldownMs) {
            LogStore.debug(TAG, "规则「${rule.name}」命中但仍在冷却（${rule.cooldownSec}s）")
            return null
        }

        val notes = mutableListOf<String>()
        if (targetSubId >= 0 && targetSubId != current) {
            if (ControlManager.setDefaultDataSubId(targetSubId)) {
                notes += "数据卡切到 subId $targetSubId"
            } else {
                notes += "切换数据卡失败（subId $targetSubId）"
            }
        }
        // 制式改的是规则自己指定的那张卡；targetSubId == -1（不切卡）时没有目标卡，跳过。
        if (rule.targetModeValue > 0 && targetSubId >= 0) {
            val m = NetworkMode.fromValue(rule.targetModeValue)
            if (m != null && ControlManager.setMode(targetSubId, m)) {
                notes += "制式设为 ${m.shortLabel}"
            }
        }
        activeMatch = ActiveMatch(rule.id, targetSubId)
        lastApplyAtMs = now
        if (notes.isEmpty()) return null
        return "Wi-Fi「${snap.wifiSsid ?: rule.name}」命中规则「${rule.name}」：${notes.joinToString("，")}"
    }

    /**
     * 规则要切过去的那张卡，自己是否允许被切。
     *
     * 卡的「总开关」关掉后语义是「任何策略都不作用在这张卡上」，那么把它切成默认数据卡
     * 也该被拦住 —— 否则用户关掉卡 2 的策略，规则却仍然把流量切到卡 2。
     * `targetSubId < 0`（不切卡）不看卡开关。
     */
    private fun targetCardAllows(policies: Map<Int, SimPolicy>, targetSubId: Int): Boolean =
        targetSubId < 0 || policies[targetSubId]?.enabled != false

    private suspend fun revertIfNeeded(
        context: Context,
        rulesById: Map<String, DataCardRule>,
        current: Int,
    ): String? {
        val prev = activeMatch ?: return null
        activeMatch = null
        val rule = rulesById[prev.ruleId]
        if (rule == null || !rule.revertOnLeave) return null
        val home = DataCardStore.homeSubId(context)
        if (home < 0 || home == current) return null
        return if (ControlManager.setDefaultDataSubId(home)) {
            "已离开 Wi-Fi，数据卡回切到 subId $home"
        } else {
            "已离开 Wi-Fi，但回切 subId $home 失败"
        }
    }

    // ---------------- 单卡独立策略 ----------------

    private suspend fun evaluatePolicies(context: Context, snap: SignalSnapshot): List<String> {
        val out = mutableListOf<String>()
        val now = System.currentTimeMillis()
        for (sim in SimReader.sims(context)) {
            val policy = DataCardStore.policyOf(context, sim.subId, sim.slotIndex)
            // 总开关关掉 = 有效策略集合为空（而不是「照旧跑」）。
            val active = if (policy.enabled) effectiveStrategies(policy) else emptySet()

            if (DataCardStrategy.WIFI_DOWNGRADE !in active) {
                // 策略没开：如果之前降级过，就要还原。
                restoreAfterWifiDowngrade(sim, now)?.let { out += it }
                continue
            }

            val throttle = lastModeWriteAt[sim.subId] ?: 0L
            if (now - throttle < MODE_WRITE_THROTTLE_MS) continue

            if (snap.isWifi) {
                applyWifiDowngrade(sim, policy, now)?.let { out += it }
            } else {
                restoreAfterWifiDowngrade(sim, now)?.let { out += it }
            }
        }
        return out
    }

    /** 连上 Wi-Fi：降到 [SimPolicy.downgradeModeValue]，并记下降级前的制式。 */
    private suspend fun applyWifiDowngrade(
        sim: SimSlotInfo,
        policy: SimPolicy,
        now: Long,
    ): String? {
        val target = policy.downgradeModeValue
        if (target <= 0) return null
        val m = NetworkMode.fromValue(target) ?: return null
        val cur = ControlManager.getMode(sim.subId)
        if (cur == target) return null
        if (!ControlManager.setMode(sim.subId, m)) return null
        // cur < 0 表示读不到当前制式（特权通道不可用），此时不记录，免得还原成垃圾值。
        if (cur > 0 && sim.subId !in savedModeBeforeWifiDowngrade) {
            savedModeBeforeWifiDowngrade[sim.subId] = cur
        }
        lastModeWriteAt[sim.subId] = now
        return "${sim.title} 因 Wi-Fi 连接降为 ${m.shortLabel}"
    }

    /** 离开 Wi-Fi（或关掉这项策略）：把制式还回降级前记录的值。只还原一次。 */
    private suspend fun restoreAfterWifiDowngrade(sim: SimSlotInfo, now: Long): String? {
        val saved = savedModeBeforeWifiDowngrade.remove(sim.subId) ?: return null
        val m = NetworkMode.fromValue(saved) ?: return null
        if (ControlManager.getMode(sim.subId) == saved) return null
        lastModeWriteAt[sim.subId] = now
        return if (ControlManager.setMode(sim.subId, m)) {
            "${sim.title} Wi-Fi 降级结束（已离开 Wi-Fi 或策略关闭），制式还原为 ${m.shortLabel}"
        } else {
            "${sim.title} Wi-Fi 降级结束，但制式还原失败"
        }
    }
}
