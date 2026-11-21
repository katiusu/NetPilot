package io.github.katiusu.netpilot.core.datacard

import android.content.Context
import io.github.katiusu.netpilot.core.mode.NetworkMode
import io.github.katiusu.netpilot.core.monitor.LogStore
import io.github.katiusu.netpilot.core.monitor.SignalSnapshot
import io.github.katiusu.netpilot.core.priv.ControlManager

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
     * 全局防抖（秒级）。**只**用来挡住「同一轮 / 相邻轮重复写」，不再当作规则冷却：
     * 规则冷却按 (ruleId, targetSubId) 单独记，见 [lastRuleApplyAt]。
     */
    private const val APPLY_DEBOUNCE_MS = 5_000L

    /** 还原失败后的重试退避：第 n 次失败等 BASE×2^(n-1)，上限 MAX。 */
    private const val RESTORE_RETRY_BASE_MS = 60_000L
    private const val RESTORE_RETRY_MAX_MS = 15 * 60_000L

    /**
     * 当前生效的匹配：命中的规则 + 这次要切的卡。
     *
     * 必须把目标卡一起记下来 —— 否则「已经切过去了」的短路判断和离开 Wi-Fi
     * 时的回切都会判错。
     */
    private data class ActiveMatch(val ruleId: String, val targetSubId: Int)

    @Volatile private var activeMatch: ActiveMatch? = null

    @Volatile private var lastApplyAtMs = 0L

    /**
     * 每条规则（按 ruleId + 目标卡）上次应用的时间，冷却以它为准。
     *
     * 以前冷却用的是全局 [lastApplyAtMs]：规则 A 应用后，规则 B（另一个 SSID / 另一张卡）
     * 在 A 的冷却期内也会被压住，用户换个 Wi-Fi 规则就不生效。
     */
    private val lastRuleApplyAt = mutableMapOf<String, Long>()

    /**
     * 每个 subId 上次**尝试**写制式的时间（Wi-Fi 降级 + 规则写制式共用，避免两套节流互相打架）。
     *
     * 记「尝试」而不是「成功」：失败也必须记时间。否则 60s 节流对失败尝试形同虚设，
     * 秒级采样会一轮接一轮去 fork app_process 重刷（每次几十 MB RSS、几秒到十几秒）。
     */
    private val lastModeWriteAt = mutableMapOf<Int, Long>()

    /** 还原失败次数与下次允许重试的时间（per subId），见 [restoreAfterWifiDowngrade]。 */
    private val restoreFailures = mutableMapOf<Int, Int>()
    private val restoreRetryAt = mutableMapOf<Int, Long>()

    /**
     * Wi-Fi 降级前的制式**落盘**在 [DataCardStore] 里（以前是这里的一张内存 Map）：
     * 进程被杀或 App 被系统回收后，内存记录会连同「原来是什么制式」一起丢失，
     * 用户就只能停在降级后的制式上。写入顺序也反了过来：先落盘再改制式。
     */

    fun matchedRule(context: Context): DataCardRule? =
        activeMatch?.ruleId?.let { id -> DataCardStore.rules(context).firstOrNull { it.id == id } }

    fun reset() {
        activeMatch = null
        lastApplyAtMs = 0L
        lastRuleApplyAt.clear()
        lastModeWriteAt.clear()
        restoreFailures.clear()
        restoreRetryAt.clear()
        // 降级前制式的记录刻意不清：它是「还原成什么」的唯一依据，
        // 换了配置也要留着，等真的还原成功再由 restoreAfterWifiDowngrade 删掉。
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

        // 冷却按「这条规则 + 这张目标卡」记：规则之间互不牵连。
        val cooldownKey = ruleKey(rule.id, targetSubId)
        if (now - (lastRuleApplyAt[cooldownKey] ?: 0L) < cooldownMs) {
            LogStore.debug(TAG, "规则「${rule.name}」命中但仍在冷却（${rule.cooldownSec}s）")
            return null
        }
        // 全局单值只剩秒级防抖：挡住同一轮采样里重复进入，不再压制别的规则。
        if (now - lastApplyAtMs < APPLY_DEBOUNCE_MS) return null

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
                // 规则刚写过这张卡的制式，记进 per-subId 节流，免得紧接着的单卡策略再写一遍。
                lastModeWriteAt[targetSubId] = now
                notes += "制式设为 ${m.shortLabel}"
            }
        }
        activeMatch = ActiveMatch(rule.id, targetSubId)
        lastApplyAtMs = now
        lastRuleApplyAt[cooldownKey] = now
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
                restoreAfterWifiDowngrade(context, sim, now)?.let { out += it }
                continue
            }

            val throttle = lastModeWriteAt[sim.subId] ?: 0L
            if (now - throttle < MODE_WRITE_THROTTLE_MS) continue

            if (snap.isWifi) {
                applyWifiDowngrade(context, sim, policy, now)?.let { out += it }
            } else {
                restoreAfterWifiDowngrade(context, sim, now)?.let { out += it }
            }
        }
        return out
    }

    /** 连上 Wi-Fi：降到 [SimPolicy.downgradeModeValue]，并**先落盘**降级前的制式。 */
    private suspend fun applyWifiDowngrade(
        context: Context,
        sim: SimSlotInfo,
        policy: SimPolicy,
        now: Long,
    ): String? {
        val target = policy.downgradeModeValue
        if (target <= 0) return null
        val m = NetworkMode.fromValue(target) ?: return null
        val cur = ControlManager.getMode(sim.subId)
        if (cur == target) return null
        // 顺序很重要：先把「原来是什么」落盘，再改制式。反过来的话，写成功的一瞬间进程被杀
        // 就永远不知道原值了（旧实现只存在内存里，且是先改后记）。
        // cur < 0 表示读不到当前制式（特权通道不可用），此时不记录，免得还原成垃圾值。
        if (cur > 0 && DataCardStore.savedDowngradeModes(context)[sim.subId] == null) {
            DataCardStore.putSavedDowngradeMode(context, sim.subId, cur, now)
        }
        // 先记时间再写：失败也算一次尝试，否则下一轮秒级采样会立刻再 fork 一次特权进程。
        lastModeWriteAt[sim.subId] = now
        if (!ControlManager.setMode(sim.subId, m)) return null
        // 这次写成功了，历史失败计数与退避作废。
        restoreFailures.remove(sim.subId)
        restoreRetryAt.remove(sim.subId)
        return "${sim.title} 因 Wi-Fi 连接降为 ${m.shortLabel}"
    }

    /**
     * 离开 Wi-Fi（或关掉这项策略）：把制式还回降级前记录的值。
     *
     * 记录**写成功之后才删**：旧实现先 remove 再 setMode，写失败就再也想不起原来是什么制式，
     * 只能停在降级后的模式上。失败时保留记录、按指数退避重试，并在日志里留下失败次数。
     */
    private suspend fun restoreAfterWifiDowngrade(
        context: Context,
        sim: SimSlotInfo,
        now: Long,
    ): String? {
        val saved = DataCardStore.savedDowngradeModes(context)[sim.subId] ?: return null
        val retryAt = restoreRetryAt[sim.subId] ?: 0L
        if (now < retryAt) return null
        val m = NetworkMode.fromValue(saved.modeValue) ?: run {
            // 记录里的值在本机模式表里已经不存在（换机 / 版本回退），留着也没法还原。
            LogStore.warn(TAG, "${sim.title} 降级前的制式 ${saved.modeValue} 不在模式表内，丢弃这条记录")
            DataCardStore.removeSavedDowngradeMode(context, sim.subId)
            return null
        }
        if (ControlManager.getMode(sim.subId) == saved.modeValue) {
            // 已经是原制式（比如用户手动改回来了）：记录用完即弃，不重复写。
            DataCardStore.removeSavedDowngradeMode(context, sim.subId)
            return null
        }
        if (ControlManager.setMode(sim.subId, m)) {
            lastModeWriteAt[sim.subId] = now
            restoreFailures.remove(sim.subId)
            restoreRetryAt.remove(sim.subId)
            DataCardStore.removeSavedDowngradeMode(context, sim.subId)
            return "${sim.title} Wi-Fi 降级结束（已离开 Wi-Fi 或策略关闭），制式还原为 ${m.shortLabel}"
        }
        val failures = (restoreFailures[sim.subId] ?: 0) + 1
        restoreFailures[sim.subId] = failures
        val backoffMs = minOf(RESTORE_RETRY_BASE_MS shl (failures - 1).coerceAtMost(4), RESTORE_RETRY_MAX_MS)
        restoreRetryAt[sim.subId] = now + backoffMs
        LogStore.warn(
            TAG,
            "${sim.title} 制式还原失败（连续第 ${failures} 次），保留降级前记录 mode=${saved.modeValue}，" +
                "${backoffMs / 1000}s 后重试"
        )
        return "${sim.title} Wi-Fi 降级结束，但制式还原失败（已排队 ${backoffMs / 1000}s 后重试）"
    }

    /** 规则冷却键：(ruleId, targetSubId)。同一个规则切不同卡 / 不同规则切同一张卡都互不影响。 */
    private fun ruleKey(ruleId: String, targetSubId: Int): String = ruleId + "@" + targetSubId
}
