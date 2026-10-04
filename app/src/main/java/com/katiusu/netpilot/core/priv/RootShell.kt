package com.katiusu.netpilot.core.priv

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 极简 `su` 执行器（不依赖 libsu —— 本项目的 Maven 镜像拉不到该 su 封装库，也不允许引入）。
 *
 * 只负责「起一个 root shell、拿 stdout/stderr、超时就杀」，不做任何解析。
 * 无 su 时 `ProcessBuilder.start()` 会抛 `IOException`，这里统一吞成 `code = -1`。
 */
object RootShell {

    private const val TAG = "NetPilot"
    private const val PROBE_TIMEOUT_MS = 5_000L

    /** null = 还没探测过。 */
    @Volatile
    private var rootAvailable: Boolean? = null

    data class ShellResult(val code: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    /**
     * 是否有可用的 su。结果带缓存，`refresh = true` 强制重测。
     *
     * 用 `su -c id` 的输出来判断（必须含 `uid=0`），避免把「su 存在但被拒绝」当成可用。
     */
    fun hasRoot(refresh: Boolean = false): Boolean {
        val cached = rootAvailable
        if (!refresh && cached != null) return cached
        val ok = try {
            val result = runProcess(listOf("su", "-c", "id"), PROBE_TIMEOUT_MS)
            !result.timedOut && result.stdout.contains("uid=0")
        } catch (e: Throwable) {
            Log.w(TAG, "hasRoot probe failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        rootAvailable = ok
        return ok
    }

    /** 执行 `su -c "<command>"`，默认 15s 超时。 */
    fun exec(command: String, timeoutMs: Long = 15_000L): ShellResult =
        runProcess(listOf("su", "-c", command), timeoutMs)

    /** 一行命令拼接辅助：把参数转义成单行 shell 命令。 */
    fun quote(args: List<String>): String = args.joinToString(" ") { quoteOne(it) }

    private fun quoteOne(arg: String): String =
        if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "._-/:@=,+" }) {
            arg
        } else {
            "'" + arg.replace("'", "'\\''") + "'"
        }

    private fun runProcess(argv: List<String>, timeoutMs: Long): ShellResult = try {
        val process = ProcessBuilder(argv).start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        // 必须并发读，否则管道写满会让子进程卡死，waitFor 一定超时。
        val outReader = Thread { readStream(process.inputStream, stdout) }
            .apply { isDaemon = true; start() }
        val errReader = Thread { readStream(process.errorStream, stderr) }
            .apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            try {
                process.waitFor(1, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                // destroy 之后进程立刻消失也可能抛，忽略
            }
            outReader.join(200L)
            errReader.join(200L)
            ShellResult(-1, stdout.toString(), stderr.toString(), true)
        } else {
            outReader.join(1_000L)
            errReader.join(1_000L)
            ShellResult(process.exitValue(), stdout.toString(), stderr.toString(), false)
        }
    } catch (e: Throwable) {
        // 无 su（IOException）/ SELinux 拒绝等，一律降级成失败结果
        Log.w(TAG, "exec failed: ${e.javaClass.simpleName}: ${e.message}")
        ShellResult(-1, "", e.message ?: e.javaClass.simpleName, false)
    }

    private fun readStream(stream: InputStream, sink: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    sink.append(buffer, 0, read)
                }
            }
        } catch (_: Throwable) {
            // 抢在 destroy() 之后被关掉的流会抛，忽略即可
        }
    }
}
