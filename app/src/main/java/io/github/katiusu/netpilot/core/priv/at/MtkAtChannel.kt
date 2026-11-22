package io.github.katiusu.netpilot.core.priv.at

import android.net.LocalSocket
import android.net.LocalSocketAddress
import io.github.katiusu.netpilot.core.priv.AtReply
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * 联发科 AT 命令的 unix socket 通道（**只给 root 子进程用**）。
 *
 * 为什么必须是 root：`/dev/socket/rild-atci` 的 SELinux 类型是 `gsmrild_socket`，
 * 只有 radio 系域有 allow，DAC 位是 0660 radio。Shizuku 的 shell uid 2000 既不在
 * radio 组、也没有 allow，连上去会被直接 EACCES —— 所以这条通道不接 Shizuku。
 *
 * 三条实现约束，每条都对应一个真实的坑：
 *
 * 1. **命名空间三段试**。`LocalSocketAddress(name)` 默认是 ABSTRACT，而 atcid 监听的是
 *    文件系统命名空间里的 `rild-atci`。直接按默认连会连到不存在的抽象名上，表现为
 *    「连接超时」，很容易被误判成服务没起来。顺序：RESERVED → FILESYSTEM → ABSTRACT。
 * 2. **必须 setSoTimeout**。协程的 withTimeout 取消不了阻塞的 read —— 它卡在 syscall 里，
 *    取消信号根本递不进去。只能靠 socket 自己的读超时。
 * 3. **短连接，不复用**。超时之后 socket 里可能还压着半条回包，复用会让下一条命令读到
 *    上一条的尾巴，把「上一次的结果」当成「这一次的结果」。
 */
class MtkAtChannel(
    private val socketName: String = DEFAULT_SOCKET,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {

    private val namespaces = listOf(
        LocalSocketAddress.Namespace.RESERVED,
        LocalSocketAddress.Namespace.FILESYSTEM,
        LocalSocketAddress.Namespace.ABSTRACT,
    )

    /**
     * 发一条 AT 命令并读到终止 token。
     *
     * 不抛异常：传输层失败一律变成 [AtReply.IoFailure]，让调用方能按「这条通道不可用」处理。
     */
    fun exec(command: String, timeoutMs: Long = TOTAL_TIMEOUT_MS): AtReply {
        val opened = open()
        if (opened.reply != null) return opened.reply
        val socket = opened.socket ?: return AtReply.IoFailure("未能建立到 " + socketName + " 的连接")
        try {
            val out = socket.outputStream
            out.write((command + "\r").toByteArray(Charsets.UTF_8))
            out.flush()
            return readReply(socket.inputStream, timeoutMs)
        } catch (e: Throwable) {
            return AtReply.IoFailure(e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 试完三种命名空间；返回连上的 socket，或一句能直接进日志的失败原因。 */
    private fun open(): Opened {
        val reasons = ArrayList<String>(namespaces.size)
        for (ns in namespaces) {
            val candidate = LocalSocket()
            try {
                candidate.connect(LocalSocketAddress(socketName, ns))
                candidate.soTimeout = readTimeoutMs
                return Opened(candidate, null)
            } catch (e: IOException) {
                reasons += ns.name + ":" + (e.message ?: e.javaClass.simpleName)
                runCatching { candidate.close() }
            }
        }
        return Opened(null, AtReply.IoFailure(
            "连接 " + socketName + " 失败（三种命名空间都试过）：" + reasons.joinToString("；")
        ))
    }

    private fun readReply(input: InputStream, timeoutMs: Long): AtReply {
        val lines = ArrayList<String>()
        val pending = StringBuilder()
        val buffer = ByteArray(512)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                return AtReply.Timeout(pending.toString())
            } catch (e: Throwable) {
                return AtReply.IoFailure("读回包失败：" + (e.message ?: e.javaClass.simpleName))
            }
            if (read <= 0) break
            pending.append(String(buffer, 0, read, Charsets.UTF_8))
            while (true) {
                val breakAt = pending.indexOf("\n")
                if (breakAt < 0) break
                val raw = pending.substring(0, breakAt)
                pending.delete(0, breakAt + 1)
                val line = raw.trim('\r', ' ', '\t')
                if (line.isEmpty()) continue
                val token = AtProtocol.terminatorOf(line)
                if (token != null) {
                    return if (AtProtocol.isFailureTerminator(token)) {
                        AtReply.Error(token, lines)
                    } else {
                        AtReply.Ok(lines)
                    }
                }
                lines += line
            }
        }
        return AtReply.Timeout(pending.toString())
    }

    private data class Opened(val socket: LocalSocket?, val reply: AtReply?)

    companion object {
        /** stock atcid 监听的 socket 名。 */
        const val DEFAULT_SOCKET = "rild-atci"

        /** 单条命令的读超时。AT 回包是毫秒级，1.5 秒读不到就是这条通道不通。 */
        const val READ_TIMEOUT_MS = 1_500

        /** 一条命令从连接到读完的总预算。 */
        const val TOTAL_TIMEOUT_MS = 5_000L
    }
}