package io.github.katiusu.netpilot.core.priv

import android.telephony.SubscriptionManager
import android.util.Log
import io.github.katiusu.netpilot.core.mode.NetworkModeBitmaskMapper
import io.github.katiusu.netpilot.core.priv.at.AtApplyResult
import io.github.katiusu.netpilot.core.priv.at.EratRat
import io.github.katiusu.netpilot.core.priv.at.MtkAtBackend
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 特权命令行入口，供 root 的 `app_process` 直接启动（Shizuku 通道不走这里，走 binder）：
 *
 * ```
 * CLASSPATH=/data/app/~~xxx/base.apk app_process /system/bin \
 *     io.github.katiusu.netpilot.core.priv.PrivilegedCli getmode 1
 * ```
 *
 * 契约：结果以 stdout 的**单行**给出（前缀见下表），然后 `System.exit(0)`。
 * 详细诊断另写在 `DIAG ` 前缀的行上（见 [WriteDiag]）；父进程按前缀各行解析，互不干扰。
 * 末尾追加 `--verbose` 只影响诊断详略，**不参与命令解析**：
 * ```
 * getmode <subId>        -> MODE <int>          (失败 MODE -1)
 * setmode <subId> <mode> -> SETMOD <true|false>
 * getslot                -> SLOT <int>
 * setslot <slot>         -> SETSLOT <true|false>
 * slots                  -> SLOTS <slot>:<subId>,...
 * probe                  -> PROBE OK | PROBE ERR <reason>
 * atprobe <slot>        -> ATPROBE <一句话结论>
 * atsetmode <mode> <slot> -> ATSET <true|false> <说明>
 * atrestore <rat> <slot> -> ATSET <true|false> <说明>
 * 其它                   -> ERR unknown
 * ```
 *
 * 注意：**不要**在这里 `Looper.prepare()` —— app_process 是直接反射调用本类的 main，
 * 没有任何 Android 生命周期，需要 Looper 的 API（如 TelephonyManager 回调）一概不用。
 * 本类同时把结果镜像到 logcat（tag `NetPilot`），方便 stdout 被 ROM 重定向时排查。
 */
object PrivilegedCli {

    private const val TAG = "NetPilot"

    /** 单条外部命令的硬超时：读管道与等退出共用这一个预算。 */
    private const val COMMAND_TIMEOUT_SEC = 5L

    /** 超时强杀后留给读线程收尾的时间（它是 daemon 线程，拿不到也不会拖住进程退出）。 */
    private const val READER_DRAIN_MS = 500L

    /** 超时统一用的退出码。 */
    private const val COMMAND_TIMEOUT_EXIT = -1

    @JvmStatic
    fun main(args: Array<String>) {
        // 子进程没有 Context，读不到用户的「详细诊断日志」设置，
        // 所以详略只能由父进程在命令行上带过来。
        WriteDiag.attachCli(args.any { it == WriteDiag.CLI_VERBOSE })
        val line = try {
            dispatch(args)
        } catch (t: Throwable) {
            Log.e(TAG, "PrivilegedCli fatal", t)
            WriteDiag.warn("cli 致命错误 " + t.javaClass.simpleName + ": " + t.message)
            "ERR " + t.javaClass.simpleName + ": " + t.message
        }
        // 外层再包一层 try：任何情况下都要 exit，不能让 root shell 挂住
        try {
            println(line)
            Log.i(TAG, "CLI_RESULT $line")
            System.out.flush()
        } catch (_: Throwable) {
            // stdout 不可用时无处可写，继续 exit
        }
        System.exit(0)
    }

    private fun dispatch(args: Array<String>): String {
        // 为什么先摘掉：它只是日志开关，混进位置参数会让 getmode/setmode 整体错位。
        val argv = args.filterNot { it == WriteDiag.CLI_VERBOSE }
        val command = argv.firstOrNull()?.trim()?.lowercase() ?: return "ERR unknown"
        val second = argv.getOrNull(1)?.trim()
        return when (command) {
            "getmode" -> {
                val subId = second?.toIntOrNull() ?: return "MODE -1"
                val mode = TelephonyReflection.getCurrentNetworkMode(subId, "cli")
                WriteDiag.detail("cli getmode subId=$subId -> $mode")
                "MODE $mode"
            }
            "setmode" -> {
                val subId = second?.toIntOrNull() ?: return "SETMOD false"
                val modeValue = argv.getOrNull(2)?.trim()?.toIntOrNull() ?: return "SETMOD false"
                val ok = setModeWithFallback(subId, modeValue)
                WriteDiag.always("cli setmode subId=$subId mode=$modeValue -> $ok")
                "SETMOD $ok"
            }
            "getslot" -> "SLOT ${TelephonyReflection.getDefaultDataSlot()}"
            "setslot" -> {
                val slot = second?.toIntOrNull() ?: return "SETSLOT false"
                val subId = SubscriptionSwitcher.resolveSubIdBySlot(slot)
                if (subId < 0) "SETSLOT false" else "SETSLOT ${SubscriptionSwitcher.setDefaultDataSubId(subId)}"
            }
            "slots" -> {
                val pairs = SubscriptionSwitcher.activeSlotList()
                if (pairs.isEmpty()) {
                    "SLOTS"
                } else {
                    "SLOTS " + pairs.joinToString(",") { "${it.first}:${it.second}" }
                }
            }
            "probe" -> TelephonyReflection.probe()?.let { "PROBE ERR $it" } ?: "PROBE OK"
            "atprobe" -> {
                val slot = second?.toIntOrNull() ?: 0
                val probe = MtkAtBackend(slot).probe()
                WriteDiag.always(
                    "cli atprobe slot=" + slot + " canRead=" + probe.canRead +
                        " canWrite=" + probe.canWrite
                )
                "ATPROBE " + probe.note
            }
            "atsetmode" -> {
                val modeValue = second?.toIntOrNull() ?: return "ATSET false 缺少制式参数"
                val slot = argv.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                val result = MtkAtBackend(slot).apply(modeValue)
                WriteDiag.detail("cli atsetmode slot=" + slot + " mode=" + modeValue)
                formatAtResult(result)
            }
            "atrestore" -> {
                val raw = second?.toIntOrNull() ?: return "ATSET false 缺少 rat 参数"
                val rat = EratRat.ofOrNull(raw) ?: return "ATSET false rat 越界（应在 0-31）"
                val slot = argv.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                formatAtResult(MtkAtBackend(slot).restore(rat))
            }
            else -> "ERR unknown"
        }
    }

    /**
     * `setmode`：先走 ITelephony 反射链；失败再用 `settings` 兜底
     * （移植自 Network_Enhance，MIT）。
     *
     * 子进程里起 `settings` 是允许的 —— 不要在 app 进程里做这件事。
     * 兜底写的键**按 subId 映射到卡槽**（规则见 [settingsKeyFor]）：槽 0 用不带后缀的
     * preferred_network_mode，槽 N 用 preferred_network_modeN。以前无论给哪张卡都先写
     * 不带后缀的键，于是给卡 2 改制式实际改的是卡 1，还因为「命令退出码 0」报成功。
     */
    private fun setModeWithFallback(subId: Int, modeValue: Int): Boolean {
        WriteDiag.detail("cli 开始切换：先走 ITelephony 三条写入策略")
        if (TelephonyReflection.setNetworkMode(subId, modeValue, "cli")) {
            WriteDiag.always("cli 由 ITelephony 策略完成切换 subId=$subId mode=$modeValue")
            return true
        }
        // 能落到这里说明 ITelephony 三条全 Miss —— 这本身就是「回读通过却切不动」的头号嫌疑，
        // 所以由 always 级别记录，不必开详细模式也能看见。
        // 1.5.0：先写**权威存储**，再退到 settings。顺序为什么是这样 ——
        // `settings put global preferred_network_mode` 写的是遗留兼容字段，写完回读必然一致
        // （自己写自己读），它在结构上就无法回答「制式到底改了没有」；而 TelephonyProvider 的
        // `siminfo.allowed_network_types` 才是 Android 11+ 系统真正读的地方。先写对的地方，
        // 写不进去才退到那个「一定成功但没有保证」的地方。
        // 1.5.1：每一步的失败原因都收在这里，最后一次性写进结论行。过程细节（命令原文、原始输出）
        // 仍然归详细开关管，但「卡在哪一步、为什么卡」必须无条件看得见 —— 否则日志里只剩一句
        // 「所有策略全部失败」，没法分析。
        var authStoreWhy = ""
        if (writeAuthStore(subId, modeValue) { authStoreWhy = it }) {
            WriteDiag.always(
                "cli ITelephony 全部失败，已写入权威存储 siminfo.allowed_network_types（subId=$subId mode=$modeValue）；" +
                    "是否下发到调制解调器仍取决于电话进程有没有观察这张表"
            )
            return true
        }
        // 1.6.x：settings 兜底的键必须跟着 subId 走。槽位映射不出来时不猜 ——
        // 猜错的代价就是「改了另一张卡还报成功」，那正是这次要修的缺陷。
        val key = settingsKeyFor(subId)
        if (key == null) {
            WriteDiag.warn(
                "cli 拿不到 subId=$subId 对应的卡槽，无法确定 settings 键名；跳过 settings 兜底" +
                    "（不猜键名，避免写到别的卡上）"
            )
            return false
        }
        var settingsWhy = ""
        if (writeSettings(key, modeValue) { settingsWhy = it }) {
            WriteDiag.always("cli ITelephony 全部失败，回落 settings put global $key=$modeValue（写入后已回读一致）")
            return true
        }
        Log.w(TAG, "setmode($subId, $modeValue): all strategies failed")
        WriteDiag.warn(
            "cli 所有写入策略全部失败 subId=$subId mode=$modeValue；为什么：" +
                "① ITelephony 三条策略全 Miss（逐策略原因见上一条警告）；" +
                "② 权威存储：" + authStoreWhy.ifEmpty { "未执行" } + "；" +
                "③ settings $key：" + settingsWhy.ifEmpty { "未执行" }
        )
        return false
    }

    /**
     * subId → settings 键名；映射不出来返回 null。
     *
     * 规则与项目现有的读法一致（RootController.slotSuffix 就是按这条拼键的）：AOSP 把这一项
     * 按 phoneId（= 卡槽）分键 —— 槽 0 是不带后缀的 preferred_network_mode，槽 N 是
     * preferred_network_modeN。这里必须跟读的那一套一致，否则写的是别人的键。
     */
    private fun settingsKeyFor(subId: Int): String? {
        val slot = runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrNull() ?: -1
        if (slot < 0) return null
        return if (slot > 0) "preferred_network_mode$slot" else "preferred_network_mode"
    }

    /**
     * 把位掩码写进权威存储，并做写入后回读。
     *
     * `content update` 的退出码只说明命令跑通了，**不代表那一列真的变成了目标值**
     * （行不存在、列不可写、被 provider 忽略都可能返回 0），所以必须回读。
     */
    private fun writeAuthStore(subId: Int, networkMode: Int, onFailure: (String) -> Unit = {}): Boolean {
        val networkTypes = NetworkModeBitmaskMapper.platform.toBitmask(networkMode) ?: run {
            WriteDiag.warn(
                "cli 模式 $networkMode 不在本机位掩码表内（表内 0..${NetworkModeBitmaskMapper.MAX_NETWORK_MODE}），" +
                    "跳过权威存储写入"
            )
            onFailure(
                "模式 $networkMode 不在本机位掩码表内（表内 0..${NetworkModeBitmaskMapper.MAX_NETWORK_MODE}），已拒绝写入"
            )
            return false
        }
        // 详细日志：先把「换算成什么掩码、要去写哪一行」讲清楚，再记原始退出码与输出。
        WriteDiag.detail(
            "cli 目标 subId=$subId mode=$networkMode -> 位掩码=$networkTypes；开始 content update siminfo"
        )
        val update = runTool(
            AuthStore.CONTENT_BIN, "update",
            "--uri", AuthStore.CONTENT_URI_STRING,
            "--where", AuthStore.COLUMN_SUB_ID + "=" + subId,
            "--bind", AuthStore.COLUMN_ALLOWED_NETWORK_TYPES + ":l:" + networkTypes
        )
        // 1.5.1：这一行从 always 降为 detail —— 它记的是**某一步的原始结果**（过程），
        // 归详细开关管；失败原因由下面的 onFailure 带进结论行，默认也看得见。
        WriteDiag.detail(
            "cli 权威存储写入：content update siminfo." + AuthStore.COLUMN_ALLOWED_NETWORK_TYPES + "=" + networkTypes +
                "（subId=$subId）-> exit=" + update.first +
                (if (update.second.isEmpty()) "" else " out=" + WriteDiag.inlineRaw(update.second))
        )
        // 详细模式：provider 的完整原始输出（可能带堆栈）分块记下来，别只留截断版。
        WriteDiag.detailBlock("cli content update 原始输出", update.second)
        if (update.first != 0) {
            // 失败时的原始退出码与 provider 输出两种模式都要记（见 WriteDiag.failure）。
            WriteDiag.failure(
                "cli 权威存储写入被拒：exit=" + update.first + " 原始输出=" + WriteDiag.inlineRaw(update.second, 240)
            )
            onFailure(
                "content update 退出码 ${update.first}" +
                    (if (update.second.isBlank()) "" else "：" + WriteDiag.inlineRaw(update.second))
            )
            return false
        }
        val back = runTool(
            AuthStore.CONTENT_BIN, "query",
            "--uri", AuthStore.CONTENT_URI_STRING,
            "--projection", AuthStore.COLUMN_SUB_ID + ":" + AuthStore.COLUMN_ALLOWED_NETWORK_TYPES,
            "--where", AuthStore.COLUMN_SUB_ID + "=" + subId
        )
        val readBack = AuthStore.parseQueryOutput(back.second, AuthStore.COLUMN_ALLOWED_NETWORK_TYPES)
        WriteDiag.detail(
            "cli 回读原文：exit=" + back.first + " 输出=" + WriteDiag.inlineRaw(back.second, 400) +
                " -> 解析值=" + (readBack?.toString() ?: "解析不出")
        )
        WriteDiag.detailBlock("cli 权威存储回读原始输出", back.second)
        val matched = back.first == 0 && readBack == networkTypes
        WriteDiag.detail(
            "cli 权威存储回读：" + (readBack?.toString() ?: "读不到") + "（期望 $networkTypes）-> " +
                (if (matched) "一致" else "不一致")
        )
        if (!matched) {
            // 回读不一致时的原始证据（退出码 + 原文 + 解析值）两种模式都要能看到。
            WriteDiag.failure(
                "cli 权威存储写后回读不一致：exit=" + back.first + " 回读原文=" + WriteDiag.inlineRaw(back.second, 240) +
                    " 解析值=" + (readBack?.toString() ?: "解析不出") + " 期望=$networkTypes"
            )
            onFailure(
                "写后回读 " + (readBack?.toString() ?: "读不到") + "，与目标 $networkTypes 不一致（exit=${back.first}）；" +
                    "权威存储里的值变了也不等于调制解调器已接受"
            )
        }
        return matched
    }

    /**
     * 起一个系统命令并等它结束，返回 (退出码, 合并后的输出)；超时统一记为退出码 -1。
     *
     * 抽出来给 content 命令复用 —— content 没有 API、也没有 SDK 常量，只能走命令行。
     */
    private fun runTool(vararg argv: String): Pair<Int, String> {
        return try {
            // 详细日志（命令原文、耗时、是否超时、完整输出）都在 [runCommand] 里。
            val outcome = runCommand(*argv)
            if (outcome.timedOut) {
                WriteDiag.warn(
                    "cli " + (argv.firstOrNull() ?: "?") + " 超过 " + COMMAND_TIMEOUT_SEC + " 秒未结束，已强制结束；" +
                        "只拿到 " + outcome.output.length + " 字符输出"
                )
            }
            outcome.exitCode to outcome.output
        } catch (e: Throwable) {
            WriteDiag.warn("cli " + argv.firstOrNull() + " 抛异常：" + e.javaClass.simpleName + ": " + e.message)
            -1 to (e.javaClass.simpleName + ": " + e.message)
        }
    }

    /** 一次系统命令的结果。[timedOut] 为真表示到点被强制结束，[output] 是已经读到的那部分。 */
    private data class CommandOutcome(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * 起一个系统命令并等它结束；读管道与等退出共用一个超时预算。
     *
     * 为什么不能用 bufferedReader().use { it.readText() }：它会一直阻塞到 EOF，特权进程卡住时
     * 后面的 waitFor(5s) 永远轮不到 —— 调用方（设置页 / QS 磁贴）就挂死，而 "读完了再等超时"
     * 这种写法等于让超时变成死代码。这里读线程只负责泵数据，主线程用有界 join 等它，
     * 到点 destroyForcibly 拆掉管道；已经读到的部分照旧返回，并由 [CommandOutcome.timedOut]
     * 明确标成超时，不吞掉。
     */
    private fun runCommand(vararg argv: String): CommandOutcome {
        val startedAt = System.currentTimeMillis()
        WriteDiag.detail("cli 执行命令：" + argv.joinToString(" "))
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val sink = ByteArrayOutputStream()
        val reader = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = try {
                            input.read(buffer)
                        } catch (_: Throwable) {
                            -1 // 超时 destroyForcibly 之后流会直接断开，这不是错误
                        }
                        if (read < 0) break
                        synchronized(sink) { sink.write(buffer, 0, read) }
                    }
                }
            } catch (_: Throwable) {
                // 管道已断（超时路径）时不做补救：已读到的内容由 sink 交出
            }
        }
        reader.name = "np-cli-reader"
        reader.isDaemon = true
        reader.start()

        val budgetMs = COMMAND_TIMEOUT_SEC * 1000L
        val exited = process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)
        // 进程已退出时管道里还有数据要收尾，多给一小段时间；还没退出就只剩共享预算。
        val remaining = if (exited) READER_DRAIN_MS else (budgetMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
        // Thread.join(long) 在 Kotlin 里返回 Unit（不是「是否已汇合」的 Boolean），
        // 所以判「排空了没有」只能看读线程自己还在不在。
        reader.join(remaining)
        val timedOut = !exited || reader.isAlive
        if (timedOut) {
            process.destroyForcibly()
            // 只等一小会儿：读线程是 daemon，真拿不到锁也不会拖住进程退出
            reader.join(READER_DRAIN_MS)
        }
        val code = if (exited) process.exitValue() else COMMAND_TIMEOUT_EXIT
        val output = String(synchronized(sink) { sink.toByteArray() }, Charsets.UTF_8).trim()
        WriteDiag.detail(
            "cli 命令结束：exit=$code 耗时=" + (System.currentTimeMillis() - startedAt) + "ms" +
                (if (timedOut) "（" + COMMAND_TIMEOUT_SEC + " 秒超时，已 destroyForcibly）" else "")
        )
        WriteDiag.detailBlock("cli 原始输出（" + (argv.firstOrNull() ?: "?") + "）", output)
        return CommandOutcome(code, output, timedOut)
    }

    /**
     * settings put + 写后回读，两步都带超时。
     *
     * 退出码 0 只说明 SettingsProvider 收下了这条记录，不代表那一列真的变成了目标值 ——
     * 键不可写、行被 provider 忽略也会返回 0。所以写之后必须 settings get 回读，
     * 读不到或读回的值不是目标值一律算失败，绝不因为「命令跑通了」就报成功。
     */
    private fun writeSettings(key: String, value: Int, onFailure: (String) -> Unit = {}): Boolean {
        return try {
            val put = runCommand("settings", "put", "global", key, value.toString())
            val ok = !put.timedOut && put.exitCode == 0
            if (!ok) {
                // 1.5.1：把这一步为什么失败交给结论行（过程原文仍留在 runCommand 的 detail 里）。
                val why = if (put.timedOut) {
                    COMMAND_TIMEOUT_SEC.toString() + " 秒超时被 destroyForcibly"
                } else {
                    "exit=" + put.exitCode
                }
                WriteDiag.failure(
                    "cli settings put global $key $value 失败：" + why +
                        (if (put.output.isEmpty()) "" else "，输出=" + put.output.take(120))
                )
                onFailure(
                    "settings put global $key $value 未成功（" + why +
                        (if (put.output.isEmpty()) "" else "，输出=" + put.output.take(120)) + "）"
                )
                return false
            }
            Log.i(TAG, "settings put global $key $value -> ok=true out=${put.output}")
            val read = runCommand("settings", "get", "global", key)
            val readBack = read.output.trim().toIntOrNull()
            if (!read.timedOut && read.exitCode == 0 && readBack == value) {
                WriteDiag.detail("cli settings get global $key -> $readBack（与目标 $value 一致）")
                return true
            }
            // 回读不一致的原始证据（退出码 + 原文 + 解析值）两种模式都要能看到。
            val readWhy = if (read.timedOut) "回读超时" else "回读 exit=" + read.exitCode
            WriteDiag.failure(
                "cli settings $key 写入后回读不一致：回读=" + (readBack?.toString() ?: "读不到") +
                    " 原文=" + WriteDiag.inlineRaw(read.output, 240) + " 期望=$value（$readWhy）"
            )
            onFailure(
                "settings $key 写后回读 " + (readBack?.toString() ?: "读不到") +
                    "，与目标 $value 不一致（$readWhy）"
            )
            false
        } catch (e: Throwable) {
            Log.w(TAG, "settings put global $key $value failed: ${e.javaClass.simpleName}: ${e.message}")
            WriteDiag.warn("cli settings put global $key $value 抛异常：${e.javaClass.simpleName}: ${e.message}")
            onFailure("settings put global $key $value 抛异常：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

}

/** 把 AT 写入结论压成契约要求的一行：ATSET <true|false> <说明>。 */
private fun formatAtResult(result: AtApplyResult): String = when (result) {
    is AtApplyResult.Succeeded -> "ATSET true " + result.note
    is AtApplyResult.AlreadyApplied -> "ATSET true " + result.note
    is AtApplyResult.Rejected -> "ATSET false " + result.reason
    AtApplyResult.Unsupported -> "ATSET false 该制式在联发科上没有确定的 rat，未写入"
}

