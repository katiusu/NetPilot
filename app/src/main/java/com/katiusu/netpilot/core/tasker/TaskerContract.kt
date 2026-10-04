package com.katiusu.netpilot.core.tasker

import android.content.Intent
import com.katiusu.netpilot.core.mode.NetworkMode

/**
 * Tasker / Locale 插件的对外协议常量（单一事实来源）。
 *
 * 为什么集中在这里：广播 action、extra 键、Locale 契约字符串一旦散落在接收器、
 * 发送器、编辑界面和文档之间，改一个名字就会漏掉别的调用点。这里统一声明，
 * 文档（docs/TASKER.md）与 Locale 编辑界面直接复用 [ACTION_SPECS]，不会再出现
 * 「代码里改了、文档里还是旧的」这种漂移。
 *
 * 约定：所有 extra 键都带 `netpilot.` 前缀；值一律是基本类型（Int / Boolean / String），
 * 不放 Parcelable 或自定义对象 —— 广播要跨进程甚至跨应用（Tasker）传递，
 * 只放基本类型最不容易出反序列化问题。
 */
object TaskerContract {

    // ---------------- 接收侧 action（Tasker → NetPilot）----------------

    /** 按 RIL 裸值切换制式，需要 `sub_id` + `mode_value`。 */
    const val ACTION_SET_NETWORK_MODE = "com.katiusu.netpilot.action.SET_NETWORK_MODE"

    /** 按预设名切换制式，需要 `sub_id` + `preset`。 */
    const val ACTION_SET_PRESET = "com.katiusu.netpilot.action.SET_PRESET"

    /** 反转「网络质量自动降级」开关，无参数。 */
    const val ACTION_TOGGLE_AUTO_DOWNGRADE = "com.katiusu.netpilot.action.TOGGLE_AUTO_DOWNGRADE"

    /** 显式设置「网络质量自动降级」开关，需要 `enabled`。 */
    const val ACTION_SET_AUTO_DOWNGRADE = "com.katiusu.netpilot.action.SET_AUTO_DOWNGRADE"

    /** 把默认数据卡切到指定卡，需要 `sub_id`。 */
    const val ACTION_SET_DATA_SIM = "com.katiusu.netpilot.action.SET_DATA_SIM"

    /** 双卡一键互换默认数据卡，无参数。 */
    const val ACTION_SWITCH_DATA_SIM = "com.katiusu.netpilot.action.SWITCH_DATA_SIM"

    /** 游戏模式：锁「仅 4G」防跳频 / 恢复，需要 `lock`。 */
    const val ACTION_LOCK_LTE = "com.katiusu.netpilot.action.LOCK_LTE"

    /** 立即采样一次并把快照回执，无参数。 */
    const val ACTION_SAMPLE_NOW = "com.katiusu.netpilot.action.SAMPLE_NOW"

    /** 读取当前状态快照（不触发采样），无参数。 */
    const val ACTION_GET_STATUS = "com.katiusu.netpilot.action.GET_STATUS"

    /** 接收侧 action 全集，接收器用它做白名单判断。 */
    val RECEIVE_ACTIONS: Set<String> = setOf(
        ACTION_SET_NETWORK_MODE,
        ACTION_SET_PRESET,
        ACTION_TOGGLE_AUTO_DOWNGRADE,
        ACTION_SET_AUTO_DOWNGRADE,
        ACTION_SET_DATA_SIM,
        ACTION_SWITCH_DATA_SIM,
        ACTION_LOCK_LTE,
        ACTION_SAMPLE_NOW,
        ACTION_GET_STATUS,
    )

    // ---------------- 发送侧 action（NetPilot → Tasker）----------------

    /** 每一条命令的回执。Tasker 用「Intent Received」订阅它拿结果与错误原因。 */
    const val ACTION_RESULT = "com.katiusu.netpilot.event.RESULT"

    /** 从「未降级」变为「已降级」。 */
    const val ACTION_DOWNGRADED = "com.katiusu.netpilot.event.DOWNGRADED"

    /** 从「已降级」恢复为「未降级」。 */
    const val ACTION_RECOVERED = "com.katiusu.netpilot.event.RECOVERED"

    /** 制式发生变化（由调用方在切换成功后发送）。 */
    const val ACTION_MODE_CHANGED = "com.katiusu.netpilot.event.MODE_CHANGED"

    /** 默认数据卡发生变化。 */
    const val ACTION_DATA_SIM_CHANGED = "com.katiusu.netpilot.event.DATA_SIM_CHANGED"

    /** 每次采样完成（监控循环驱动，频率 = 监控间隔）。 */
    const val ACTION_SIGNAL_SAMPLED = "com.katiusu.netpilot.event.SIGNAL_SAMPLED"

    // ---------------- extra 键（统一 netpilot. 前缀）----------------

    /** Int：SIM 卡的 subId（SubscriptionManager 的 subscriptionId，大于 0）。 */
    const val EXTRA_SUB_ID = "netpilot.sub_id"

    /** Int：RIL 首选网络模式裸值（0..33，见 core/mode/NetworkMode.kt）。 */
    const val EXTRA_MODE_VALUE = "netpilot.mode_value"

    /** String：预设名（5g / 5g_only / 4g / 4g_only / 3g / 2g）。 */
    const val EXTRA_PRESET = "netpilot.preset"

    /** Boolean：开关目标状态。 */
    const val EXTRA_ENABLED = "netpilot.enabled"

    /** Boolean：是否锁定 LTE（游戏模式）。 */
    const val EXTRA_LOCK = "netpilot.lock"

    /** Boolean：命令是否执行成功。 */
    const val EXTRA_RESULT_OK = "netpilot.result_ok"

    /** String：人类可读的执行结果 / 失败原因。 */
    const val EXTRA_RESULT_MESSAGE = "netpilot.result_message"

    /** String：单行状态文本，形如 `5G 自动 · RSRP -92 dBm · 35 ms · 未降级`。 */
    const val EXTRA_STATUS_TEXT = "netpilot.status_text"

    /** String：展示用网络制式名，如 `NR_SA` / `LTE` / `WLAN` / `未知`。 */
    const val EXTRA_NETWORK_TYPE = "netpilot.network_type"

    /** Int：RSRP（dBm，负数）；读不到时不带这个 extra。 */
    const val EXTRA_RSRP = "netpilot.rsrp"

    /** Int：SINR（dB）；读不到时不带这个 extra。 */
    const val EXTRA_SINR = "netpilot.sinr"

    /** Int：一次 TCP 握手往返毫秒数；无响应时不带这个 extra。 */
    const val EXTRA_PING_MS = "netpilot.ping_ms"

    /** Boolean：当前是否处于「已降级」状态。 */
    const val EXTRA_DOWNGRADED = "netpilot.downgraded"

    /** String：当前生效的特权通道（Root / Shizuku / 无）。 */
    const val EXTRA_CHANNEL = "netpilot.channel"

    // ---------------- Locale 插件专用键（同样 netpilot. 前缀）----------------

    /**
     * String：Locale Bundle 里真正要执行的动作，取值是上面 9 个接收侧 action 之一。
     *
     * 为什么不直接用 Intent 的 action：Locale 插件的 Intent action 固定是
     * `com.twofortyfouram.locale.intent.action.FIRE_SETTING`，真实动作只能放进 Bundle，
     * 所以需要一个额外的键来承载它。
     */
    const val EXTRA_PLUGIN_ACTION = "netpilot.plugin_action"

    /** String：条件查询类型，见 [CONDITION_DOWNGRADED] 等。 */
    const val EXTRA_PLUGIN_CONDITION = "netpilot.plugin_condition"

    /** 条件：当前是否已降级。 */
    const val CONDITION_DOWNGRADED = "downgraded"

    /** 条件：自动降级开关是否打开。 */
    const val CONDITION_AUTO_DOWNGRADE_ENABLED = "auto_downgrade_enabled"

    /** 条件：监控是否在运行。 */
    const val CONDITION_MONITOR_RUNNING = "monitor_running"

    // ---------------- 选项清单（编辑界面 / 文档复用）----------------

    /** 预设名 → 中文说明。顺序即界面上拉菜单的顺序。 */
    val PRESET_OPTIONS: List<Pair<String, String>> = listOf(
        "5g" to "5G 自动（NR/LTE 自动）",
        "5g_only" to "仅 5G (NR)",
        "4g" to "4G 自动（LTE/3G/2G）",
        "4g_only" to "仅 4G (LTE)",
        "3g" to "3G 自动",
        "2g" to "仅 2G (GSM)",
    )

    /** 编辑界面下拉里提供的常用制式裸值，中文名由 [modeLabel] 给出。 */
    val MODE_OPTIONS: List<Int> = listOf(11, 9, 10, 23, 24, 26, 27, 32, 1, 2)

    /** 条件查询 → 中文说明。 */
    val CONDITION_OPTIONS: List<Pair<String, String>> = listOf(
        CONDITION_DOWNGRADED to "当前已降级（5G 已自动降到 4G）",
        CONDITION_AUTO_DOWNGRADE_ENABLED to "自动降级开关已打开",
        CONDITION_MONITOR_RUNNING to "信号监控正在运行",
    )

    /** 制式裸值 → 中文短名；未知值原样回显，便于排错。 */
    fun modeLabel(value: Int): String =
        NetworkMode.fromValue(value)?.label ?: "未知制式值 $value"

    /** 预设名 → 中文说明；未知名字原样回显。 */
    fun presetLabel(key: String): String =
        PRESET_OPTIONS.firstOrNull { it.first == key }?.second ?: key

    /**
     * 构造回执 Intent。
     *
     * 注意：**不要**对回执调用 `setPackage(自己的包名)`。`setPackage` 会把广播
     * 限制成「只发给本应用的接收者」，而回执的接收方是 Tasker（另一个包），
     * 加了 package 之后 Tasker 的「Intent Received」永远收不到。回执是隐式广播，
     * 由 Tasker 运行时注册的接收者接收，这是 Android 8 以后仍然允许的路径。
     */
    fun resultIntent(ok: Boolean, message: String): Intent =
        Intent(ACTION_RESULT)
            .putExtra(EXTRA_RESULT_OK, ok)
            .putExtra(EXTRA_RESULT_MESSAGE, message)

    /**
     * 全部接收侧 action 的规格表（兼容别名，等价于顶层 [TASKER_ACTION_SPECS]）。
     * 用 getter 而不是直接初始化，避免对象初始化顺序依赖顶层属性。
     */
    val ACTION_SPECS: List<TaskerActionSpec> get() = TASKER_ACTION_SPECS

    /** action → 规格（兼容别名，等价于顶层 [TASKER_ACTION_SPEC_BY_ACTION]）。 */
    val SPEC_BY_ACTION: Map<String, TaskerActionSpec> get() = TASKER_ACTION_SPEC_BY_ACTION
}

/**
 * 一条接收侧 action 的自描述规格：给文档和 Locale 编辑界面/调试界面复用，
 * 避免「参数说明写在文档里，代码改了文档不知道」。
 *
 * @property action 广播 action 全名。
 * @property title 中文标题（下拉菜单显示）。
 * @property params 参数说明（人类可读，含类型）。
 * @property example 可直接复制粘贴的 adb 命令示例。
 */
data class TaskerActionSpec(
    val action: String,
    val title: String,
    val params: String,
    val example: String,
)

/** 全部接收侧 action 的规格表，顺序与文档一致。 */
val TASKER_ACTION_SPECS: List<TaskerActionSpec> = listOf(
    TaskerActionSpec(
        TaskerContract.ACTION_SET_NETWORK_MODE,
        "切换制式（裸值）",
        "netpilot.sub_id (Int，必填) + netpilot.mode_value (Int，必填，0..33)",
        "adb shell am broadcast -a com.katiusu.netpilot.action.SET_NETWORK_MODE " +
            "--ei netpilot.sub_id 1 --ei netpilot.mode_value 11",
    ),
    TaskerActionSpec(
        TaskerContract.ACTION_SET_PRESET,
        "切换制式（预设名）",
        "netpilot.sub_id (Int，必填) + netpilot.preset (String，必填：5g/5g_only/4g/4g_only/3g/2g)",
        "adb shell am broadcast -a com.katiusu.netpilot.action.SET_PRESET " +
            "--ei netpilot.sub_id 1 --es netpilot.preset 4g_only",
    ),
    TaskerActionSpec(
        TaskerContract.ACTION_TOGGLE_AUTO_DOWNGRADE,
        "反转自动降级开关",
        "（无参数）",
        "adb shell am broadcast -a com.katiusu.netpilot.action.TOGGLE_AUTO_DOWNGRADE",
    ),
    TaskerActionSpec(
        TaskerContract.ACTION_SET_AUTO_DOWNGRADE,
        "设置自动降级开关",
        "netpilot.enabled (Boolean，必填)",
        "adb shell am broadcast -a com.katiusu.netpilot.action.SET_AUTO_DOWNGRADE " +
            "--ez netpilot.enabled true",
    ),
    TaskerActionSpec(
        TaskerContract.ACTION_SET_DATA_SIM,
        "切换默认数据卡",
        "netpilot.sub_id (Int，必填)",
        "adb shell am broadcast -a com.katiusu.netpilot.action.SET_DATA_SIM " +
            "--ei netpilot.sub_id 2",
    ),
    TaskerActionSpec(
        TaskerContract.ACTION_SWITCH_DATA_SIM,
        "双卡一键互换",
        "（无参数）",
        "adb shell am broadcast -a com.katiusu.netpilot.action.SWITCH_DATA_SIM",
    ),
    TaskerActionSpec(
        TaskerContract.ACTION_LOCK_LTE,
        "锁定 / 恢复仅 4G",
        "netpilot.lock (Boolean，必填：true=锁仅 4G，false=恢复运营商默认)",
        "adb shell am broadcast -a com.katiusu.netpilot.action.LOCK_LTE " +
            "--ez netpilot.lock true",
    ),
    TaskerActionSpec(
        TaskerContract.ACTION_SAMPLE_NOW,
        "立即采样一次",
        "（无参数）",
        "adb shell am broadcast -a com.katiusu.netpilot.action.SAMPLE_NOW",
    ),
    TaskerActionSpec(
        TaskerContract.ACTION_GET_STATUS,
        "读取状态快照",
        "（无参数）",
        "adb shell am broadcast -a com.katiusu.netpilot.action.GET_STATUS",
    ),
)

/** action → 规格，便于按 action 反查参数说明。 */
val TASKER_ACTION_SPEC_BY_ACTION: Map<String, TaskerActionSpec> =
    TASKER_ACTION_SPECS.associateBy { it.action }
