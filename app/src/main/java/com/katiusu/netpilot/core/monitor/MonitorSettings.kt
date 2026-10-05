package com.katiusu.netpilot.core.monitor

import android.content.Context
import android.telephony.TelephonyManager
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.prefs.ConfigState

/**
 * 网络质量相关配置的键与默认值。
 *
 * 键名里的 `np_fake5g_` 前缀沿用旧命名、**必须保留**：老用户已经写在 SharedPreferences
 * 里的值靠它才读得回来，重命名等于把所有人的配置清空。改名只发生在用户可见文案上。
 *
 * 默认值**逐条对应** Network_Enhance `config.sh`，键名统一加 `np_` 前缀，
 * 与模板里 `example_*` 的示例键不冲突。UI 侧用 [OptionSpec] 引用这里的常量，
 * 保证「默认值只有一处定义」。
 */
object MonitorSettings {

    const val KEY_ENABLED = "np_fake5g_enabled"
    const val KEY_RSRP = "np_fake5g_rsrp"
    const val KEY_SINR = "np_fake5g_sinr"
    const val KEY_PING = "np_fake5g_ping"
    const val KEY_COOLDOWN = "np_fake5g_cooldown"
    const val KEY_RECOVERY = "np_fake5g_recovery"
    const val KEY_NO_NET_ROLLBACK = "np_fake5g_no_net_rollback"
    const val KEY_INTERVAL = "np_fake5g_interval"
    const val KEY_DOWNGRADE_MODE = "np_fake5g_downgrade_mode"
    const val KEY_LOCK_LTE_MODE = "np_fake5g_lock_lte_mode"
    const val KEY_PING_FAIL = "np_fake5g_ping_fail"

    /** 「假满格」是否只在 5G / 5G+ 上判定（默认开，见 [DEFAULT_FAKE5G_NR_ONLY]）。 */
    const val KEY_NR_ONLY = "np_fake5g_nr_only"

    const val KEY_TOGGLE_ENDC = "np_fake5g_endc"

    /**
     * 「信号过差」规则的 RSRP 门限，单位 dBm。
     *
     * 与 [KEY_RSRP] 是**两条独立的规则**：那个管「信号满格但没质量」，这个管
     * 「信号本身就差」。两者共用同一个键会互相干扰，所以各留一个。
     */
    const val KEY_WEAK_RSRP = "np_fake5g_weak_rsrp"

    /** 是否启用「信号过差」降级；关掉后弱信号只记录、不修改制式。 */
    const val KEY_WEAK_SIGNAL = "np_fake5g_weak_signal"

    const val KEY_AUTO_START = "np_monitor_autostart"

    /** 自适应采样间隔的总开关。 */
    const val KEY_ADAPTIVE_INTERVAL = "np_fake5g_adaptive_interval"

    /** 自适应「靠近门限」的宽度（dBm）。 */
    const val KEY_ADAPTIVE_MARGIN = "np_fake5g_adaptive_margin"

    /** 自适应每连续靠近一轮，采样间隔乘的系数（1.4.0 起可调，之前是硬编码 0.8）。 */
    const val KEY_ADAPTIVE_STEP = "np_fake5g_adaptive_step"

    /**
     * 网络质量降级的默认开关状态：**默认开启**。
     *
     * 这是本应用的核心功能，装完即生效比「先去功能页找一个开关打开」更符合预期。
     * 关掉它只影响「是否写网络制式」，采样、判定与日志照旧跑。
     * 这是默认值的**唯一真源**，[com.katiusu.netpilot.core.NetPilot.autoDowngradeEnabled]
     * 也读它，避免两处各写一个字面量后改一处漏一处。
     */
    const val DEFAULT_ENABLED = true

    const val DEFAULT_RSRP = -85
    const val DEFAULT_SINR = 0

    /**
     * 「假满格」是否只在 5G / 5G+ 上判定的默认值：**默认开**。
     *
     * 这条限制修的是「驻留在 4G 时被 ping 拖进假满格降级、然后永久锁在 4G」，
     * 完整因果链见 [DowngradeThresholds.fakeFullBarOnNrOnly] 与 docs/POWER_REPORT.md §7。
     * 默认开是因为默认值下的误判代价（锁死 4G）远高于漏判代价（该降没降，用户还能手动切）。
     */
    const val DEFAULT_FAKE5G_NR_ONLY = true

    /** Ping 上限默认 300 ms（1.4.0 从 200 提高，原因见 [DowngradeThresholds.pingThresholdMs]）。 */
    const val DEFAULT_PING = 300
    /**
     * 降级冷却默认 120 秒（1.4.0 从 60 提高）。
     *
     * 上游脚本用 1800 秒（30 分钟）太长：降级本来就发生在「满格但跑不动」时，冷却过久会让
     * 网络已经恢复还长时间停在 4G。但 60 秒又偏短 —— 刚降到 4G 的那一两轮读数往往还没稳，
     * 太早开始累计恢复轮数会让制式来回抖。滑条下限 30 秒（见 FeaturesPage 的 KEY_COOLDOWN 规格）。
     */
    const val DEFAULT_COOLDOWN = 120
    /** 恢复正常默认 2 轮：3 轮在 60 秒采样间隔下要等 3 分钟才对「已经好了」有反应。 */
    const val DEFAULT_RECOVERY = 2
    const val DEFAULT_NO_NET_ROLLBACK = 2
    /** 采样间隔默认 60 秒；技术下限 15 秒（MonitorEngine 与滑条范围都按它收敛）。 */
    const val DEFAULT_INTERVAL = 60
    const val DEFAULT_DOWNGRADE_MODE = 9
    /** 0 = 跟随运营商（5G 自动），见 DowngradeThresholds.lockLteMode。 */
    const val DEFAULT_LOCK_LTE_MODE = 0
    /** 「信号过差」门限默认 -110 dBm，与 Network_Enhance 的弱信号判据一致。 */
    const val DEFAULT_WEAK_RSRP = -110
    /** 「信号过差」规则默认开启：弱 5G 往往比稳定的 4G 更慢、更耗电。 */
    const val DEFAULT_WEAK_SIGNAL = true

    /** 自适应采样间隔默认开启：用户要的是「该快的时候快」，而不是自己算什么时候该快。 */
    const val DEFAULT_ADAPTIVE_INTERVAL = true

    /** 自适应灵敏度默认 20 dBm（含义见 [DowngradeThresholds.adaptiveMarginDbm]）。 */
    const val DEFAULT_ADAPTIVE_MARGIN = 20

    /**
     * 每连续靠近门限一轮，采样间隔乘的系数；默认 0.85（= 每轮缩短 15%）。
     *
     * 1.3.0 里它是硬编码常量 `ADAPTIVE_STEP_FACTOR = 0.8`；1.4.0 起由用户通过
     * [KEY_ADAPTIVE_STEP] 调整，这里只保留默认值。放在这里而不是 MonitorEngine：
     * 它和 [ADAPTIVE_MIN_FACTOR] 是一对，分开容易只改一半 —— 步长改小了却不改下限，
     * 间隔就会一步撞到底。
     */
    const val DEFAULT_ADAPTIVE_STEP = 0.85f

    /**
     * [KEY_ADAPTIVE_STEP] 的滑条范围与步长。
     *
     * 低于 0.5 时两三轮就撞上 [ADAPTIVE_MIN_FACTOR]，自适应退化成一开就到底；
     * 高于 0.95 则每轮只缩几个百分点，等于没缩。两端都是「这个滑块调了也没意义」。
     */
    const val ADAPTIVE_STEP_MIN = 0.5f
    const val ADAPTIVE_STEP_MAX = 0.95f
    const val ADAPTIVE_STEP_STEP = 0.05f

    /**
     * 缩短的下限：最多缩到配置间隔的一半，且永不突破技术下限 15 秒。
     *
     * 用「比例」而不是绝对秒数：[KEY_INTERVAL] 允许把间隔设到 1800 秒，一个绝对值下限
     * 对 15 秒和 1800 秒的含义完全不同（前者会被下限顶住等于没自适应，后者会缩到极密），
     * 比例下限对两端都成立。
     */
    const val ADAPTIVE_MIN_FACTOR = 0.5f
    /**
     * 读取整型配置。
     *
     * 下拉卡片把选中的是 `entryValues` 里的**字符串**（如 "9"），滑块写的是 Float，
     * 所以这里不能直接用 `ConfigState.int`——它只接受 Number，遇到字符串会静默
     * 退回默认值，表现就是「下拉选了但策略没变」。统一在这里做一次收敛。
     */
    private fun intValue(key: String, default: Int): Int = when (val raw = ConfigState.get(key)) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull() ?: default
        else -> default
    }

    /**
     * 读取自适应倍率，并夹进 [ADAPTIVE_STEP_MIN]..[ADAPTIVE_STEP_MAX]。
     *
     * 收敛逻辑与 [intValue] 相同（滑块写 Float、导入的配置可能是字符串）；额外的夹取
     * 是必须的：这个值会直接进 `Math.pow`，一旦配置被写成 0 或负数，采样间隔会塌成
     * 0 秒 —— 那等于把监控循环变成没有间隔的死循环。
     */
    private fun stepFactorValue(): Float {
        val raw = when (val v = ConfigState.get(KEY_ADAPTIVE_STEP)) {
            is Number -> v.toFloat()
            is String -> v.trim().toFloatOrNull()
            else -> null
        } ?: return DEFAULT_ADAPTIVE_STEP
        return raw.coerceIn(ADAPTIVE_STEP_MIN, ADAPTIVE_STEP_MAX)
    }

    fun thresholds(): DowngradeThresholds = DowngradeThresholds(
        enabled = ConfigState.bool(KEY_ENABLED, DEFAULT_ENABLED),
        rsrpThreshold = intValue(KEY_RSRP, DEFAULT_RSRP),
        sinrThreshold = intValue(KEY_SINR, DEFAULT_SINR),
        pingThresholdMs = intValue(KEY_PING, DEFAULT_PING),
        cooldownSec = intValue(KEY_COOLDOWN, DEFAULT_COOLDOWN),
        recoveryCount = intValue(KEY_RECOVERY, DEFAULT_RECOVERY),
        noNetRollbackCount = intValue(KEY_NO_NET_ROLLBACK, DEFAULT_NO_NET_ROLLBACK),
        monitorIntervalSec = intValue(KEY_INTERVAL, DEFAULT_INTERVAL),
        downgradeMode = intValue(KEY_DOWNGRADE_MODE, DEFAULT_DOWNGRADE_MODE),
        lockLteMode = intValue(KEY_LOCK_LTE_MODE, DEFAULT_LOCK_LTE_MODE),
        downgradeOnPingFail = ConfigState.bool(KEY_PING_FAIL, false),
        fakeFullBarOnNrOnly = ConfigState.bool(KEY_NR_ONLY, DEFAULT_FAKE5G_NR_ONLY),
        toggleEndc = ConfigState.bool(KEY_TOGGLE_ENDC, false),
        weakRsrpThreshold = effectiveWeakRsrp(),
        downgradeOnWeakSignal = ConfigState.bool(KEY_WEAK_SIGNAL, DEFAULT_WEAK_SIGNAL),
        adaptiveIntervalEnabled = ConfigState.bool(KEY_ADAPTIVE_INTERVAL, DEFAULT_ADAPTIVE_INTERVAL),
        adaptiveMarginDbm = intValue(KEY_ADAPTIVE_MARGIN, DEFAULT_ADAPTIVE_MARGIN),
        adaptiveStepFactor = stepFactorValue(),
    )

    /**
     * 读弱信号阈值，并保证它严格低于强信号阈值。
     *
     * 为什么必须收敛：两条规则的前提分别是 `RSRP > 高阈值`（有资格判假满格）与
     * `RSRP < 低阈值`（判定信号过差直接降级）。一旦低阈值被设成不低于高阈值，
     * 同一个 RSRP 会同时落进两条规则的前提里，而判定顺序上「强信号」优先，结果就是
     * 用户明明把低阈值拉到 -80，实际却按高阈值的边界在动作 —— 这种「填的数字没生效」
     * 是最难自查的一类问题，所以在读取侧直接收敛掉。
     *
     * 只收敛生效值，不改用户存下来的数字：他把滑条调回合理区间，配置立刻复原。
     */
    private fun effectiveWeakRsrp(): Int {
        val strong = intValue(KEY_RSRP, DEFAULT_RSRP)
        val weak = intValue(KEY_WEAK_RSRP, DEFAULT_WEAK_RSRP)
        return if (weak < strong) weak else strong - 1
    }
}

/** 读取运营商信息，用于把「恢复」写回该运营商的默认模式。 */
object CarrierInfo {

    /**
     * 取指定卡的运营商默认模式值；读不到时退回模式 26（NR/LTE/GSM/WCDMA）。
     *
     * 用 `TelephonyManager.getSimOperator()` 而不是 `SubscriptionManager`：
     * 前者是公开 API、不需要 READ_PHONE_STATE，返回形如 `"46003"` 的 MCC+MNC 串；
     * 而 `SubscriptionManager.getActiveSubscriptionInfoList()` 的静态形式不在公开 SDK 里
     * （只有实例方法，需要 Context + 运行时电话权限），`getActiveSubscriptionInfo(int)`
     * 同样是 @hide。SIM 未就绪时 `simOperator` 会是空串，交给 [NetworkMode.carrierDefault] 兜底。
     */
    fun defaultModeForActiveSubscription(context: Context, subId: Int): Int {
        val (mcc, mnc) = simMccMnc(context, subId)
        val mode = runCatching { NetworkMode.carrierDefault(mcc, mnc) }.getOrNull()
        return (mode ?: NetworkMode.fromValue(26))?.value ?: 26
    }

    /**
     * 识别到的运营商名（「中国移动」…），识别不出返回空串。
     *
     * 为什么单独暴露一段：设置页要能显示「这台机器把这个号段认成了哪家」。运营商默认值表
     * 本质是猜，猜得对不对必须看得见，否则改错了也没人知道。
     * 用的是同一份 MCC/MNC，所以它和 [defaultModeForActiveSubscription] 的结果永远一致。
     */
    fun activeCarrierName(context: Context, subId: Int): String {
        val (mcc, mnc) = simMccMnc(context, subId)
        return runCatching { NetworkMode.carrierName(mcc, mnc) }.getOrNull().orEmpty()
    }

    /**
     * 一行「识别结果 + 会用的默认制式」，给设置页的兼容性卡片用（1.5.0 新增）。
     *
     * 格式：`中国移动（46000）→ 32 NR/LTE/TDSCDMA/GSM`。表里没有这张卡时**明确写出来**，
     * 而不是悄悄回落到 26 —— 回落是代码行为，但只有被看见才可能被发现是错的。
     * 与 [defaultModeForActiveSubscription] 用同一份 MCC/MNC，两者结果永远一致。
     */
    fun activeCarrierSummary(context: Context, subId: Int): String {
        val (mcc, mnc) = simMccMnc(context, subId)
        if (mcc == null || mnc == null) return "读不到 SIM 的 MCC/MNC"
        val mccMnc = mcc + mnc
        val mode = runCatching { NetworkMode.carrierDefault(mcc, mnc) }.getOrNull()
            ?: return "读到了 MCC/MNC（$mccMnc）但取不到默认制式"
        val label = runCatching { NetworkMode.shortLabelOf(mode.value) }.getOrDefault("")
        val name = runCatching { NetworkMode.carrierName(mcc, mnc) }.getOrNull()
        return if (name != null) {
            "$name（$mccMnc）→ ${mode.value} $label"
        } else {
            "不在内置运营商表中（$mccMnc）→ 回落 ${mode.value} $label"
        }
    }

    /** `getSimOperator()` 的 MCC/MNC 拆分；长度不足（SIM 未就绪）时两者都是 null。 */
    private fun simMccMnc(context: Context, subId: Int): Pair<String?, String?> {
        val mccMnc = runCatching {
            val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val scoped = if (subId >= 0) manager?.createForSubscriptionId(subId) else manager
            scoped?.simOperator.orEmpty()
        }.getOrDefault("")
        val mcc = if (mccMnc.length >= 5) mccMnc.substring(0, 3) else null
        val mnc = if (mccMnc.length >= 5) mccMnc.substring(3) else null
        return mcc to mnc
    }

    /** 运营商展示名，读不到返回空串。 */
    fun operatorName(context: Context, subId: Int): String = runCatching {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val scoped = if (subId >= 0) manager?.createForSubscriptionId(subId) else manager
        scoped?.networkOperatorName.orEmpty()
    }.getOrDefault("")
}
