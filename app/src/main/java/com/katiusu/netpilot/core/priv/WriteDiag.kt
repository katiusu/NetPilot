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
 * 两档模式（设置页「详细日志模式」开关在两者间切换，默认简要）：
 *  - **简要模式**：[always] / [warn] / [failure] 都记录 —— 也就是「发生了什么」加上
 *    「失败时系统到底回了什么」。失败路径上的原始返回值（退出码、stdout/stderr、异常原文、
 *    拒绝原话）必须在这一档就能看到，否则简要模式会退化成「只写失败、不写原因」，日志没法分析；
 *  - **详细模式**：再加 [detail] —— 逐候选的每一次尝试、每一步的原始输出都记。
 *
 * 为什么默认简要：root 通道下的详细诊断要从 `app_process` 子进程跨进程搬回应用进程，属于纯
 * 诊断开销，不该成为常态；而「结论 + 失败原因 + 失败时的原始返回值」是排障的底线，那一档永远开着。
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

    /** 用户服务进程回传诊断行时，等级与正文之间的分隔符（Binder 字符串里换行不可靠）。 */
    private const val REMOTE_FIELD_SEP = '\u0001'

    /** 回传字符串里行与行的分隔符：日志正文不会出现 NUL。 */
    private const val REMOTE_LINE_SEP = '\u0000'

    /** 服务进程单条诊断行的字符上限（与 LogStore 的单条上限一致）。 */
    private const val REMOTE_LINE_CHARS = 2_000

    /** 服务进程最多攒多少行：每次 binder 调用结束就会被取走，正常到不了上限。 */
    // 1.5.2：从 400 降到 120。这份缓冲在**用户服务进程**里（Shizuku 用 app_process 拉起的
    // :np_service），那个进程常驻后台，而每次 binder 调用结束都会 drain 一次，正常最多堆几条；
    // 400 行的上限只在「应用进程长时间不调用」时才会被吃满，纯属白占内存。
    private const val REMOTE_MAX_LINES = 120

    /** 单次回传的字符上限：Binder 事务上限 1MB，不能为了日志把通道撑炸。 */
    private const val REMOTE_DRAIN_CHARS = 120_000

    private const val TAG = "写入诊断"

    /** 详细模式下每一块原始输出的字符上限（LogStore 单条上限 2000，留出标题与序号的余量）。 */
    private const val DETAIL_CHUNK_CHARS = 1_600

    /** 无条件行（结论 / 失败原因）里引用原始输出时的截断长度：够看出是哪一句拒绝，又不淹没结论。 */
    const val RAW_INLINE_CHARS = 160

    @Volatile
    private var verbose = false

    /** true = 当前在 `app_process` 子进程里（没有 Context，只能写 stdout）。 */
    @Volatile
    private var cliMode = false

    /**
     * true = 当前在 Shizuku 用户服务进程里。
     *
     * 那个进程由 Shizuku 用 app_process 拉起来，**没有** Application、也没有 LogStore 的
     * Context：`LogStore.log` 只会写进那个进程自己的内存缓冲，谁也读不到。所以这里改成
     * 「先缓存，等应用进程经 `drainDiag()` 取走」—— 这就是 1.5.2 补上 Shizuku 侧日志的办法。
     */
    @Volatile
    private var remoteMode = false

    /** 服务进程待回传的诊断行（编码见 [drainRemote]）；只在 [remoteMode] 为真时有内容。 */
    private val remoteBuffer = ArrayDeque<String>()

    /** 服务进程里还攒着多少行（自检与断言用）。 */
    val remotePending: Int get() = synchronized(remoteBuffer) { remoteBuffer.size }

    /** 当前是否处于详细模式；父进程据此决定要不要给子进程带 [CLI_VERBOSE]。 */
    val isVerbose: Boolean get() = verbose

    /**
     * 日志详略档位。
     *
     * 为什么用「模式」而不是「开关」来讲话：拿到一份日志的人要先知道它是哪一档 ——
     * 简要模式里看不到逐候选过程是设计如此，不是日志丢了。
     */
    enum class Mode(val label: String) {
        /** 结论 + 失败原因 + 失败时系统返回的原始值。 */
        BRIEF("简要模式"),

        /** 再加每一步的原始输出与逐候选尝试过程。 */
        DETAILED("详细模式"),
    }

    /** 当前档位。 */
    val mode: Mode get() = if (verbose) Mode.DETAILED else Mode.BRIEF

    /** 当前档位名，写进日志与界面。 */
    val modeLabel: String get() = mode.label

    fun enabled(context: Context?): Boolean {
        val app = context?.applicationContext ?: return DEFAULT_VERBOSE
        runCatching { ConfigState.init(app) }
        return ConfigState.bool(KEY_VERBOSE, DEFAULT_VERBOSE)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ConfigState.init(context.applicationContext)
        ConfigState.set(KEY_VERBOSE, enabled)
        verbose = enabled
        LogStore.info(
            TAG,
            if (enabled) "写入日志改为详细模式：结论、失败原因与每一步原始输出都会记录"
            else "写入日志改为简要模式：只记结论、失败原因与失败时系统返回的原始值"
        )
    }

    /**
     * 应用进程启动时调用一次：把用户设置读进内存。
     * 为什么缓存：打点就在写入链路上，每一步都去读 SharedPreferences 会给切换平白加 I/O。
     */
    fun attach(context: Context?) {
        cliMode = false
        remoteMode = false
        verbose = enabled(context)
    }

    /** 子进程入口调用：详细模式由父进程通过命令行传入。 */
    fun attachCli(verboseFlag: Boolean) {
        cliMode = true
        verbose = verboseFlag
    }

    /**
     * Shizuku 用户服务进程入口调用一次：诊断输出改成「先缓存、等应用进程取走」。
     *
     * 这里**不看**详细开关：服务进程不知道应用进程那一档（两个进程各有一份内存状态），
     * 但把 detail 一并攒下来几乎不花钱（每次调用结束就取走），档位过滤统一放到应用进程做
     * （见 [forwardRemote]）—— 这样「详细模式」在 Shizuku 通道和 root 通道上语义一致。
     */
    fun attachRemote() {
        remoteMode = true
        cliMode = false
        verbose = false
    }

    /**
     * 最近一条带原因的失败信息，供上层把它并进「结论行」。
     *
     * 为什么要有它：失败原因产生在写入链路深处（[warn]/[detail]），而用户真正看的那一行结论
     * 在 `NetPilot.setMode` —— 那里只拿得到 `Boolean`。1.5.1 之前用户看到的只有「切换失败」
     * 四个字，没法分析；现在原因跟着结论走。
     */
    @Volatile
    private var lastFailure: String = ""

    /** 关键节点：无论详细模式是否开启都要能在日志页看见。 */
    fun always(message: String) = emit(message, LogLevel.INFO, unconditional = true)

    /** 失败/异常等需要醒目标注的关键节点；同时把原因记下，等结论行取用。 */
    fun warn(message: String) {
        rememberFailure(message)
        emit(message, LogLevel.WARN, unconditional = true)
    }

    /**
     * 失败路径上的**原始系统返回值**（退出码、stdout/stderr、异常原文、拒绝原话）。
     *
     * 为什么它不能是 [detail]：这些值是「为什么失败」的唯一证据，两种模式都必须记 ——
     * 用户在简要模式下导出的日志也得能拿来分析。它同时进 [rememberFailure]，供结论行取用。
     */
    fun failure(message: String) {
        rememberFailure(message)
        emit(message, LogLevel.WARN, unconditional = true)
    }

    /** 逐候选的尝试过程：只有详细模式开启时记录；也是「最深一层的失败原因」。 */
    fun detail(message: String) {
        // 只有真的会输出时才记：详细开关关着时，detail 不该影响结论行的内容。
        // 服务进程例外：那边一律先攒下来，等应用进程按档位决定去留（见 [attachRemote]）。
        if (verbose || remoteMode) rememberFailure(message)
        emit(message, LogLevel.DEBUG, unconditional = false)
    }

    /**
     * 详细模式专用：把一段**完整原始输出**按行切成若干条日志写出去。
     *
     * 为什么必须切：LogStore 单条上限 2000 字符（`MAX_MESSAGE_CHARS`），一整段 `content query`
     * 的原始 dump 或一条异常栈直接塞进去会被砍掉尾巴 —— 而排障时最要紧的往往正是最后几行
     * （provider 最终那句拒绝、调制解调器返回的最后一句）。切块之后每条都完整，按顺序读即可。
     */
    fun detailBlock(title: String, body: String) {
        if (!verbose && !remoteMode) return
        val text = body.trim()
        if (text.isEmpty()) {
            detail("$title：（空）")
            return
        }
        val chunks = chunkLines(text, DETAIL_CHUNK_CHARS)
        chunks.forEachIndexed { index, chunk ->
            val suffix = if (chunks.size > 1) "（${index + 1}/${chunks.size}）" else ""
            detail("$title$suffix $chunk")
        }
    }

    /**
     * 供**无条件**行（结论 / 失败原因）引用原始输出：压成一行并截断。
     *
     * 为什么要压成一行：结论行本身要能一眼看完，原始输出里的换行会把后面的四步原因挤走；
     * 完整输出由 [detailBlock] 在详细模式里单独给出，两条路径分工明确。
     */
    fun inlineRaw(text: String, maxChars: Int = RAW_INLINE_CHARS): String {
        val oneLine = text.trim().replace("\r\n", " / ").replace('\n', ' ').trim()
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
    }

    /** 按行切块；单行超长时硬切，绝不丢字符。 */
    private fun chunkLines(text: String, maxChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.lineSequence()) {
            if (current.isNotEmpty() && current.length + line.length + 1 > maxChars) {
                chunks += current.toString()
                current.setLength(0)
            }
            if (line.length > maxChars) {
                var rest = line
                while (rest.length > maxChars) {
                    chunks += rest.take(maxChars)
                    rest = rest.drop(maxChars)
                }
                current.append(rest)
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /** 记下一条失败原因（后写的覆盖先写的）。 */
    fun rememberFailure(reason: String) {
        if (reason.isNotBlank()) lastFailure = reason
    }

    /** 取走最近一条失败原因；取后清空，避免下一次结论行带上过期的原因。 */
    fun consumeFailure(): String {
        val reason = lastFailure
        lastFailure = ""
        return reason
    }

    /**
     * 看一眼最近一条失败原因但**不**清空。
     *
     * 用途：调用方要判断「是不是已经有更精确的原因」—— 例如 Shizuku 通道里，服务侧回传的
     * 逐条原因比应用侧那句兜底说明精确得多，不该被后写的兜底覆盖。
     */
    fun peekFailure(): String = lastFailure

    /**
     * 父进程把子进程一条 [CLI_PREFIX] 诊断行转发进日志页。
     * 子进程里没有 LogStore 上下文，只能这样绕回来 —— 这正是详细模式有额外开销的原因。
     */
    fun forwardCliLine(line: String) {
        val message = line.trim().removePrefix(CLI_PREFIX).trim()
        if (message.isEmpty()) return
        LogStore.info(TAG, message)
    }

    /**
     * 取走并清空服务进程攒下的诊断行（`IShizukuController.drainDiag()` 的实现）。
     *
     * 编码：每行 `等级首字母 + \u0001 + 正文`，行与行之间用 `\u0000` —— 日志正文里不会出现
     * 这两个字符，所以一个 Binder 字符串就够了。取不完的部分留在缓存里等下一次。
     */
    fun drainRemote(): String {
        val sb = StringBuilder()
        synchronized(remoteBuffer) {
            while (remoteBuffer.isNotEmpty()) {
                val line = remoteBuffer.first()
                if (sb.isNotEmpty() && sb.length + line.length + 1 > REMOTE_DRAIN_CHARS) break
                remoteBuffer.removeFirst()
                if (sb.isNotEmpty()) sb.append(REMOTE_LINE_SEP)
                sb.append(line)
            }
        }
        return sb.toString()
    }

    /**
     * 应用进程把 [drainRemote] 取回的编码行按**当前档位**写进日志页。
     *
     * 等级映射与 root 通道的 `DIAG ` 前缀转发一致：
     *  - `D`（detail）→ 只在详细模式出现，并像本地 detail 一样参与「最深一层原因」；
     *  - `W` / `E`（失败）→ 两种模式都记，且进结论行的原因；
     *  - 其余（结论）→ 两种模式都记。
     */
    fun forwardRemote(raw: String?) {
        if (raw.isNullOrEmpty()) return
        for (line in raw.split(REMOTE_LINE_SEP)) {
            if (line.isEmpty()) continue
            val sep = line.indexOf(REMOTE_FIELD_SEP)
            if (sep <= 0) {
                always(line)
                continue
            }
            val message = line.substring(sep + 1)
            if (message.isBlank()) continue
            when (line[0]) {
                'D' -> detail(message)
                'W' -> warn(message)
                'E' -> failure(message)
                else -> always(message)
            }
        }
    }

    /** 服务进程：把一条诊断行编好放进待回传缓冲（超上限丢最旧的）。 */
    private fun appendRemote(level: LogLevel, message: String) {
        val line = level.name[0] + REMOTE_FIELD_SEP.toString() + message.take(REMOTE_LINE_CHARS)
        synchronized(remoteBuffer) {
            remoteBuffer.addLast(line)
            while (remoteBuffer.size > REMOTE_MAX_LINES) remoteBuffer.removeFirst()
        }
    }

    private fun emit(message: String, level: LogLevel, unconditional: Boolean) {
        if (remoteMode) {
            // Shizuku 用户服务进程：先攒着，档位过滤留给应用进程（那边才知道详细开关的状态）。
            appendRemote(level, message)
            return
        }
        if (!unconditional && !verbose) return
        if (cliMode) {
            // 子进程写 stdout，由 RootController 逐行转发进日志页。
            println(CLI_PREFIX + message)
            return
        }
        LogStore.log(TAG, message, level)
    }
}
