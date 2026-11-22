package io.github.katiusu.netpilot.core.priv.at

import io.github.katiusu.netpilot.core.priv.AtReply

/** 一次 AT 探测的结论。[canRead] 与 [canWrite] 分开：只有读没写能力时，UI 该说「可读不可写」。 */
data class AtProbeResult(
    val handshake: AtReply,
    val range: EratRange?,
    val status: EratStatus?,
    val canRead: Boolean,
    val canWrite: Boolean,
    /** 人话结论，直接进日志页。 */
    val note: String,
)

/** 一次写入的结论。没有「也许成功」这一档 —— 回读对不上就是 [Rejected]。 */
sealed interface AtApplyResult {
    /** 回读确认生效。[note] 里会写明是否宽松映射（多开了一档）。 */
    data class Succeeded(val note: String) : AtApplyResult

    /** 写之前当前集合就已覆盖目标，按「无需写入」返回成功。 */
    data class AlreadyApplied(val note: String) : AtApplyResult

    /** 读不到、写不回、或回读不匹配。 */
    data class Rejected(val reason: String) : AtApplyResult

    /** 这个制式在联发科上没有确定的 rat，调用方必须落回下一条策略。 */
    data object Unsupported : AtApplyResult
}

/**
 * 联发科 AT 直写后端：读 → 判 → 写 → 验。
 *
 * 这是整个实验版的核心，正确性全靠下面四条顺序约束（审核逐条改出来的）：
 *
 * - **写前必读**。MTK 的 RIL 会跳过「和当前值一样」的写请求，硬写会拿到旧回读，
 *   被判成失败。所以先读一次，集合已覆盖目标就直接返回 [AtApplyResult.AlreadyApplied]。
 * - **写完等一会儿再读**。modem 处理 AT+ERAT 是异步的，立刻读几乎必然读到旧值。
 * - **失败重读一次再判**。600ms 只是经验值，慢平台可能更久；直接判失败会让
 *   「其实已经写成功」变成假失败，进而触发上层的回滚逻辑，把用户网断掉。
 * - **CDMA 位从比较里剥掉**，但从不写进 modem。
 *
 * 整个类只在 root 子进程（PrivilegedCli）里构造，App 进程不要直接 new。
 */
class MtkAtBackend(
    private val slot: Int = 0,
    private val channel: MtkAtChannel = MtkAtChannel(socketNameFor(slot)),
) {

    private val lock = Any()

    /** 探测：握手 → 方言（AT+ERAT=?）→ 当前值（AT+ERAT?）。全部同步，调用方是短命的 CLI 进程。 */
    fun probe(): AtProbeResult = synchronized(lock) {
        val handshake = channel.exec("AT")
        if (handshake !is AtReply.Ok) {
            return AtProbeResult(
                handshake = handshake,
                range = null,
                status = null,
                canRead = false,
                canWrite = false,
                note = "AT 握手失败：" + handshake.brief(),
            )
        }
        val range = readRange()
        val status = readStatus()
        val canRead = status != null && status.ratCandidates.isNotEmpty()
        // 方言判据用 =? 取参数个数（那是范围查询），不是用 ? 的返回值 —— 两者回包形状不同，
        // 拿 ? 判方言会得到一个「看起来能写」的错误结论。
        val canWrite = canRead && range != null && (range.paramCount ?: 0) >= 2
        AtProbeResult(
            handshake = handshake,
            range = range,
            status = status,
            canRead = canRead,
            canWrite = canWrite,
            note = buildProbeNote(range, status, canRead, canWrite),
        )
    }

    /** 把某个 NetworkMode 写进 modem，并回读确认。 */
    fun apply(modeValue: Int): AtApplyResult = synchronized(lock) {
        val mapping = MtkEratTable.mapNetworkMode(modeValue)
            ?: return AtApplyResult.Unsupported
        val before = readStatus()
            ?: return AtApplyResult.Rejected("写前读不到当前 rat，放弃写入")
        if (beforeMaskCovers(before, mapping.targetMask)) {
            return AtApplyResult.AlreadyApplied(
                "当前已是 " + MtkEratTable.describeRat(mapping.rat) + " 覆盖的集合，跳过写入"
            )
        }
        val written = writeMapping(mapping)
        if (written != null) return AtApplyResult.Rejected(written)
        val verifier = verify(mapping.targetMask)
        return when (verifier) {
            true -> AtApplyResult.Succeeded(successNote(mapping))
            false -> AtApplyResult.Rejected(
                "写入后回读仍是 " + afterText() + "，未覆盖目标 " + mapping.targetMask + "（未重试）"
            )
        }
    }

    /** 手动恢复：把指定的 rat 写回去，不判映射，只回读确认。 */
    fun restore(rat: EratRat): AtApplyResult = synchronized(lock) {
        val mask = MtkEratTable.maskOf(rat)
            ?: return AtApplyResult.Rejected("回档 rat " + rat + " 不在已知表内")
        val prefer = MtkEratTable.preferFor(mask)
        val written = writeRaw(rat, prefer)
        if (written != null) return AtApplyResult.Rejected(written)
        val target = mask.without(Generations.CDMA)
        return if (verify(target)) {
            AtApplyResult.Succeeded("已写回 " + MtkEratTable.describeRat(rat))
        } else {
            AtApplyResult.Rejected("写回 " + rat + " 后回读不匹配")
        }
    }

    /** 写一次并等 modem 落定。返回 null 表示写命令本身成功，否则是失败原因。 */
    private fun writeMapping(mapping: EratMapping): String? {
        writeRaw(mapping.rat, mapping.prefer)?.let { return it }
        return null
    }

    private fun writeRaw(rat: EratRat, prefer: Int): String? {
        val reply = channel.exec("AT+ERAT=" + rat.raw + "," + prefer)
        return when (reply) {
            is AtReply.Ok -> null
            is AtReply.Error -> "写命令被拒：" + reply.brief()
            is AtReply.Timeout -> "写命令超时：" + reply.brief()
            is AtReply.NotSupported -> "写命令不支持"
            is AtReply.IoFailure -> "写命令通道故障：" + reply.reason
        }
    }

    /**
     * 回读确认：先等 [SETTLE_MS]，读一次；不匹配再等 [RETRY_SETTLE_MS] 读第二次。
     *
     * 两次都不匹配才返回 false —— 这一步是唯一能证明「真的写进去了」的地方，
     * 少读一次就可能把慢平台上的成功判成失败。
     */
    private fun verify(target: GenerationMask): Boolean {
        sleep(SETTLE_MS)
        readStatus()?.let { if (beforeMaskCovers(it, target)) return true }
        sleep(RETRY_SETTLE_MS)
        readStatus()?.let { if (beforeMaskCovers(it, target)) return true }
        return false
    }

    private fun successNote(mapping: EratMapping): String {
        val base = "已写入 " + MtkEratTable.describeRat(mapping.rat)
            + "，prefer=" + mapping.prefer + "，回读确认生效"
        return if (mapping.lenient) base + "（宽松映射：" + mapping.note + "）" else base
    }

    private fun buildProbeNote(
        range: EratRange?,
        status: EratStatus?,
        canRead: Boolean,
        canWrite: Boolean,
    ): String {
        if (!canRead) return "握手成功但读不到 AT+ERAT? 的当前值，只能确认通道通"
        val ratText = status!!.ratCandidates.joinToString("/") { raw ->
            val mask = EratRat.ofOrNull(raw)?.let { MtkEratTable.maskOf(it) }
            raw.toString() + "=" + (mask?.toString() ?: "表外")
        }
        return "当前 rat " + ratText + "；方言参数量 " + (range?.paramCount ?: -1)
            + "；" + if (canWrite) "可读可写" else "只读（=？回包不足两参，拒写）"
    }

    /** modem 任一回读字段能覆盖目标就算命中。字段语义没有权威来源，所以「任一候选」而不是指定字段。 */
    private fun beforeMaskCovers(status: EratStatus, target: GenerationMask): Boolean =
        status.ratCandidates.any { raw ->
            val rat = EratRat.ofOrNull(raw) ?: return@any false
            MtkEratTable.maskOf(rat)?.let { MtkEratTable.covers(it, target) } ?: false
        }

    private fun readStatus(): EratStatus? {
        val reply = channel.exec("AT+ERAT?")
        if (reply !is AtReply.Ok) return null
        return AtProtocol.parseEratStatus(reply)
    }

    private fun readRange(): EratRange? {
        val reply = channel.exec("AT+ERAT=?")
        if (reply !is AtReply.Ok) return null
        return AtProtocol.parseEratRange(reply)
    }

    private fun afterText(): String = readStatus()?.logText() ?: "读不到"

    private fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    companion object {
        /** 写入后等 modem 落定。经验值，真机日志里会记录实际值便于调整。 */
        private const val SETTLE_MS = 600L

        /** 第一次回读不匹配时的二次等待。 */
        private const val RETRY_SETTLE_MS = 900L

        /** 双卡：slot 0 用 rild-atci，slot 1 用 rild-atci2（存在才切）。 */
        private fun socketNameFor(slot: Int): String =
            if (slot <= 0) MtkAtChannel.DEFAULT_SOCKET else "rild-atci2"
    }
}