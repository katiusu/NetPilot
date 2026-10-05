package com.katiusu.netpilot.core.priv

import android.content.Context
import com.katiusu.netpilot.core.monitor.LogLevel
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.prefs.ConfigState

/**
 * 特权写入链路的诊断日志出口。
 *
 * 为什么需要它：一次制式切换要穿过「反射拿 ITelephony -> 三条写入策略 -> settings 兜底 ->
 * 回读」四层，任何一层静默失败，从外面看到的都只是「开关拨了、网络没变」。
 * 改造前这条链路只写 `android.util.Log`（只有 logcat 看得到），应用内日志页一片空白，
 * 所以无从判断卡在哪一步；`TelephonyReflection.dispatch` 更是把每个候选组合抛出的异常
 * 直接吞掉，连 logcat 里都看不到失败原因。
 *
 * 两档详略：
 *  - [always] / [warn]：关键节点（选了哪条策略、modem 返回了什么、回读到什么），无条件记录；
 *  - [detail]：逐候选的尝试过程与异常原因，只有用户在设置页打开「写入详细诊断日志」
 *    （[KEY_VERBOSE]，默认关）才记录。
 *
 * 为什么默认关：root 通道下的诊断要从 `app_process` 子进程跨进程搬回应用进程，属于纯诊断
 * 开销，不该成为常态。
 *
 * 出口按当前进程二选一：
 *  - 应用进程（Shizuku 通道 / RootController 本进程侧）：直接写 [LogStore]，进日志页；
 *  - `app_process` 子进程（root 通道）：写 stdout 并加 [CLI_PREFIX] 前缀，由
 *    `com.katiusu.netpilot.core.priv.root.RootController` 转发回日志页。
 */
object WriteDiag {

    /** 详细诊断开关的配置键（设置页「系统兼容性」卡片）。 */
    const val KEY_VERBOSE = "np_verbose_log"

    /** 默认关：详细诊断是排障手段，不是常态开销。 */
    const val DEFAULT_VERBOSE = false

    /** 父进程追加到 `app_process` 命令行末尾的详细模式标志。 */
    const val CLI_VERBOSE = "--verbose"

    /** 子进程 stdout 上诊断行的前缀；父进程据此识别并转发。 */
    const val CLI_PREFIX = "DIAG "

    private const val TAG = "写入诊断"

    @Volatile
    private var verbose = false

    /** true = 当前在 `app_process` 子进程里（没有 Context，只能写 stdout）。 */
    @Volatile
    private var cliMode = false

    /** 当前是否处于详细模式；父进程据此决定要不要给子进程带 [CLI_VERBOSE]。 */
    val isVerbose: Boolean get() = verbose

    fun enabled(context: Context?): Boolean {
        val app = context?.applicationContext ?: return DEFAULT_VERBOSE
        runCatching { ConfigState.init(app) }
        return ConfigState.bool(KEY_VERBOSE, DEFAULT_VERBOSE)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ConfigState.init(context.applicationContext)
        ConfigState.set(KEY_VERBOSE, enabled)
        verbose = enabled
        LogStore.info(TAG, if (enabled) "详细诊断日志已开启" else "详细诊断日志已关闭")
    }

    /**
     * 应用进程启动时调用一次：把用户设置读进内存。
     * 为什么缓存：打点就在写入链路上，每一步都去读 SharedPreferences 会给切换平白加 I/O。
     */
    fun attach(context: Context?) {
        cliMode = false
        verbose = enabled(context)
    }

    /** 子进程入口调用：详细模式由父进程通过命令行传入。 */
    fun attachCli(verboseFlag: Boolean) {
        cliMode = true
        verbose = verboseFlag
    }

    /** 关键节点：无论详细模式是否开启都要能在日志页看见。 */
    fun always(message: String) = emit(message, LogLevel.INFO, unconditional = true)

    /** 失败/异常等需要醒目标注的关键节点。 */
    fun warn(message: String) = emit(message, LogLevel.WARN, unconditional = true)

    /** 逐候选的尝试过程：只有详细模式开启时记录。 */
    fun detail(message: String) = emit(message, LogLevel.DEBUG, unconditional = false)

    /**
     * 父进程把子进程一条 [CLI_PREFIX] 诊断行转发进日志页。
     * 子进程里没有 LogStore 上下文，只能这样绕回来 —— 这正是详细模式有额外开销的原因。
     */
    fun forwardCliLine(line: String) {
        val message = line.trim().removePrefix(CLI_PREFIX).trim()
        if (message.isEmpty()) return
        LogStore.info(TAG, message)
    }

    private fun emit(message: String, level: LogLevel, unconditional: Boolean) {
        if (!unconditional && !verbose) return
        if (cliMode) {
            // 子进程写 stdout，由 RootController 逐行转发进日志页。
            println(CLI_PREFIX + message)
            return
        }
        LogStore.log(TAG, message, level)
    }
}
