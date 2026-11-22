package io.github.katiusu.netpilot.core.priv

/**
 * 一次 AT 命令的应答。
 *
 * 放在 core/priv 而不是 core/priv/at：这个类型是对外契约（通道有没有 AT 能力），
 * 而 core/priv/at 放传输与协议实现。依赖方向因此保持单向 —— at → priv，priv 不反向引用 at。
 */
sealed interface AtReply {

    /** 正常结束（收到 OK）。[lines] 是去掉终止行之后的全部回包行。 */
    data class Ok(val lines: List<String>) : AtReply

    /** 调制解调器明确拒绝。[token] 形如 `ERROR` / `+CME ERROR: 3`。 */
    data class Error(val token: String, val lines: List<String>) : AtReply

    /** 超时。[partial] 是已经读到的部分，用来判断卡在哪一步。 */
    data class Timeout(val partial: String) : AtReply

    /** 本通道不具备 AT 能力（非联发科 / 开关关闭 / 没有可用节点）。 */
    data object NotSupported : AtReply

    /** 传输层失败（socket 不存在、SELinux 拒绝、打开失败）。 */
    data class IoFailure(val reason: String) : AtReply

    /** 给日志页的一行摘要，不含原始回包正文。 */
    fun brief(): String = when (this) {
        is Ok -> "OK（" + lines.size + " 行回包）"
        is Error -> "拒绝：" + token
        is Timeout -> "超时（已读 " + partial.length + " 字符）"
        is IoFailure -> "传输失败：" + reason
        NotSupported -> "不支持"
    }
}