package com.katiusu.netpilot.core.monitor

/**
 * 一次采样的完整快照。
 *
 * 所有可空字段都表示「读不到」，与「读到 0」严格区分：网络质量判定依赖这个区分
 * （读不到 RSRP 时不能认定为强信号）。
 */
data class SignalSnapshot(
    val timeMs: Long = 0L,
    val subId: Int = -1,
    val slot: Int = -1,
    /** 展示用网络制式名，如 `NR_SA` / `LTE` / `WLAN` / `未知`。 */
    val networkType: String = "未知",
    /** `TelephonyManager.NETWORK_TYPE_*` 原始值，0 表示未知。 */
    val rawNetworkType: Int = 0,
    val operatorName: String = "",
    /** 参考信号接收功率，单位 dBm，恒为负数。 */
    val rsrp: Int? = null,
    /** 信号与干扰噪声比，单位 dB。 */
    val sinr: Int? = null,
    /** 一次 HTTP 探测的首字节往返毫秒数；`null` = 全部目标失败/超时。 */
    val pingMs: Int? = null,
    /** 实际使用的探测目标 URL；可能是内置回退目标而不是配置里的主目标。 */
    val pingTarget: String = "",
    /** 探测全部失败时的原因，供界面与日志显示 `null` 时排查。 */
    val pingError: String? = null,
    /**
     * 本轮**没有真的探测**（[pingMs] 与 [pingError] 同时为 null 只是因为这个）。
     *
     * 为什么必须把这个含义单独拎出来：`pingMs == null` 原本只有一个含义「探了但没通」，
     * 它同时被状态机的「无网回退」计数（`noResponse`）和界面文案依赖。省电优化会在
     * 「屏幕关闭 + 未降级 + 信号非强」时跳过整轮探测（那种组合下没有任何读者会读
     * pingMs，见 [FakeSignalDetector.judge] 的分支顺序），此时如果不能把「没测」与
     * 「测了失败」分开，界面就会把「省电没测」显示成「网络不通」——那才是真正的误报。
     */
    val probeSkipped: Boolean = false,
    val isWifi: Boolean = false,
    val wifiSsid: String? = null,
    val wifiBssid: String? = null,
    /** SINR 是从哪个来源取到的，例如 `NR ssSinr` / `LTE rssnr` / `CellInfo NR ssSinr`。 */
    val sinrSource: String? = null,
    /**
     * 一个来源都没取到时的**结构化原因**。
     *
     * 界面用它映射本地化文案，所以「为什么 SINR 是未知」能说清楚：是没给权限、
     * 没开定位，还是这张卡/这个小区本来就不上报 —— 三种情况用户能做的事完全不同。
     */
    val sinrReasonKind: SinrUnavailableReason? = null,
) {
    /**
     * 一个来源都没取到时的可读中文原因，永远与 [sinrReasonKind] 一致。
     *
     * 用计算属性而不是再存一个字段：两个字段一旦不同步，界面和日志就会互相打架。
     */
    val sinrReason: String? get() = sinrReasonKind?.label

    /** 信号「满格」：RSRP 严格强于阈值（与 Network_Enhance 的 `|RSRP| < 85` 一致）。 */
    fun isStrongSignal(rsrpThreshold: Int): Boolean = rsrp != null && rsrp > rsrpThreshold
}

/**
 * SINR 读不到时的具体原因。
 *
 * 只在 [SignalSnapshot.sinr] 为 null 时有值。之前的实现把「读不到」一律显示成
 * 「未知」，用户无法区分「没给电话权限」「没开定位」「这张卡不上报 SINR」——
 * 前者去授权就能解决，后者做什么都没用。
 */
enum class SinrUnavailableReason(val label: String) {
    /** 未驻留蜂窝网络（无卡 / 无信号，或只有 Wi-Fi）。 */
    NO_CELLULAR("未驻留蜂窝网络，没有可读的 SINR"),

    /** 缺 READ_PHONE_STATE：signalStrength 与 allCellInfo 都读不到。 */
    MISSING_PHONE_STATE("缺少「电话状态」权限（READ_PHONE_STATE），系统拒绝提供信号强度"),

    /** 缺 ACCESS_FINE_LOCATION：Android 12+ 读小区信息（allCellInfo）的硬性前提。 */
    MISSING_FINE_LOCATION("缺少「精确位置」权限（ACCESS_FINE_LOCATION），无法读取小区信息"),

    /** 系统定位服务未开启：Android 12+ 读小区信息的前提。 */
    LOCATION_SERVICES_OFF("系统定位服务未开启，Android 12+ 不允许读取小区信息"),

    /** LTE 小区上报 rssnr = UNAVAILABLE。 */
    LTE_RSSNR_UNAVAILABLE("当前 LTE 小区未上报 rssnr（SINR），该小区或固件不提供质量读数"),

    /** NR 小区上报 ssSinr = UNAVAILABLE。 */
    NR_SS_SINR_UNAVAILABLE("当前 NR 小区未上报 ssSinr，该小区或固件不提供 5G SINR"),

    /** 其余情况：机型 / 制式未上报。 */
    NOT_REPORTED("该机型或当前制式未上报 SINR（部分 ROM 会屏蔽该字段）"),
}

/**
 * 需要降级的成因。
 *
 * [FakeJudgement.fake] 只回答「要不要降」，成因决定日志文案与用户能不能看懂
 * 「为什么我的 5G 被切掉了」。
 */
enum class DowngradeReason {
    /** 不需要降级。 */
    NONE,
    /** 假满格：RSRP 够强但质量差（Ping 超阈值 / SINR 过低 / Ping 全失败）。 */
    FAKE_FULL_BAR,
    /** 信号过差：RSRP 低于 [DowngradeThresholds.weakRsrpThreshold]。 */
    WEAK_SIGNAL,
}

/** 网络质量降级的全部可调阈值，默认值沿用 Network_Enhance。 */
data class DowngradeThresholds(
    val enabled: Boolean = true,
    /** RSRP 门槛；`RSRP > -85` 才算强信号。 */
    val rsrpThreshold: Int = -85,
    /** SINR 门槛；`SINR < 0` 视为质量差。 */
    val sinrThreshold: Int = 0,
    /** Ping 门槛；`Ping > 200ms` 视为质量差。 */
    val pingThresholdMs: Int = 200,
    /**
     * 降级后多久内不尝试恢复（秒）。
     *
     * 原脚本用 1800（30 分钟），实测太保守：一旦降级，半小时内即使网络早已恢复也
     * 不会回到 5G，用户会以为「5G 丢了」。这里默认 60 秒（1 分钟），滑条下限 30 秒。
     */
    val cooldownSec: Int = 60,
    /** 冷却结束后累计多少轮正常才恢复。 */
    val recoveryCount: Int = 2,
    /** 降级态下连续多少轮无网/无响应就整体回退。 */
    val noNetRollbackCount: Int = 2,
    /** 后台监控轮询间隔（秒）。 */
    val monitorIntervalSec: Int = 60,
    /** 降级目标模式：9 = LTE/GSM/WCDMA，保留 3G 回退与 EN-DC。 */
    val downgradeMode: Int = 9,
    /**
     * 恢复（以及解除 LTE 锁定）时写回的目标制式。
     *
     * `0` = **跟随运营商，即「5G 自动」**，由 [MonitorSettings.CarrierInfo] 按 MCC/MNC
     * 推导；其余值直接用 [com.katiusu.netpilot.core.mode.NetworkMode] 的 value。
     * 默认 0 而不是 11：用户要的是「降级完能自己回到 5G」。
     */
    val lockLteMode: Int = 0,
    /**
     * 探测目标 URL，默认取本机（Xiaomi HyperOS）自己的联网校验地址。
     *
     * 用 HTTP 而不是「裸 IP + 端口」的原因见 [SignalReader.ping]：国内运营商对
     * TCP 53/443 的过滤会让纯 IP 探测整片失效。
     */
    val pingTarget: String = SignalReader.DEFAULT_PING_TARGET,
    val pingTimeoutMs: Int = 2500,
    /**
     * 弱信号门槛（dBm）；RSRP 低于它即视为「信号过差」。
     *
     * 这是与「假满格」相反的另一种该切 4G 的场景：-115 dBm 的 NR 往往比稳定的 LTE
     * 更慢更耗电，弱 5G 不如稳 4G。
     */
    val weakRsrpThreshold: Int = -110,
    /** 信号过差时是否也降级；默认开，可与「假满格」规则分别关闭。 */
    val downgradeOnWeakSignal: Boolean = true,
    /**
     * Ping 完全失败时是否也算「网络质量差」。
     *
     * Network_Enhance 的原始脚本里这个分支**几乎不可能触发**（`se_get_ping_ms()` 永远返回
     * 数字或哨兵值 2000，不会返回空串），所以默认关掉以保持行为等价；打开后等价于脚本
     * 作者原本的意图。
     */
    val downgradeOnPingFail: Boolean = false,
    /** 降级/恢复是否同时写 endc_capability（部分机型需关闭 EN-DC 才能落到 4G）。 */
    val toggleEndc: Boolean = false,
)

/** 引擎对外暴露的阶段，仅用于展示。 */
enum class MonitorPhase {
    /** 监控关闭。 */
    OFF,
    /** 正常监控中。 */
    WATCHING,
    /** 已降级，处于冷却期。 */
    DOWNGRADED_COOLDOWN,
    /** 冷却结束，正在累计正常轮数。 */
    RECOVERING,
    /** 降级后无网，正在回退。 */
    ROLLBACK,
}

/** 需要持久化的降级状态。原脚本把这些放在进程内局部变量，进程被杀后会卡在 4G。 */
data class DowngradeState(
    val active: Boolean = false,
    val degradedAtMs: Long = 0L,
    val recoveryCount: Int = 0,
    val noNetFailCount: Int = 0,
    /** 我们写下去的模式值，-1 表示没写过。 */
    val appliedMode: Int = -1,
    /** 降级前读到的模式值，-1 表示读不到，恢复时退回运营商默认值。 */
    val savedMode: Int = -1,
    val lastEvent: String = "",
    val lastEventAtMs: Long = 0L,
) {
    fun phase(nowMs: Long, thresholds: DowngradeThresholds): MonitorPhase = when {
        !active -> if (thresholds.enabled) MonitorPhase.WATCHING else MonitorPhase.OFF
        nowMs - degradedAtMs < thresholds.cooldownSec * 1000L -> MonitorPhase.DOWNGRADED_COOLDOWN
        noNetFailCount > 0 -> MonitorPhase.ROLLBACK
        else -> MonitorPhase.RECOVERING
    }
}
