package io.github.katiusu.netpilot.core.priv.at

import io.github.katiusu.netpilot.core.priv.AtReply

/**
 * AT+ERAT 的 rat 参数 —— 调制解调器自己的命名空间。
 *
 * 必须与 [GenerationMask] 严格分开，原因不是洁癖：rat 15 是「仅 5G」，而世代掩码 15 是
 * 「全制式」；rat 26 是「含 CDMA 的组合」，掩码 26 是「3G+4G+5G+CDMA」。两个命名空间混用
 * 一次，判据的方向就恰好反过来 —— 把「肯定不对」判成「成功」。用两个 value class 挡住任何
 * 隐式互转，只在 [MtkEratTable] 查表处转换。
 */
@JvmInline
value class EratRat(val raw: Int) {
    override fun toString(): String = "rat" + raw
    companion object {
        /** rat 的取值范围按 0..31 收，表外的值一律不认。 */
        fun ofOrNull(raw: Int): EratRat? = if (raw in 0..31) EratRat(raw) else null
    }
}

/**
 * 世代集合位掩码：GSM=1、UMTS=2、LTE=4、NR=8、CDMA=16。
 *
 * 这是「这组制式打开哪几代网络」，与 RIL 的 NetworkMode 常量、AT 的 rat 参数都不是一回事。
 */
@JvmInline
value class GenerationMask(val bits: Int) {

    operator fun contains(other: GenerationMask): Boolean = (bits and other.bits) == other.bits

    operator fun plus(other: GenerationMask): GenerationMask = GenerationMask(bits or other.bits)

    fun without(other: GenerationMask): GenerationMask = GenerationMask(bits and other.bits.inv())

    val isEmpty: Boolean get() = bits == 0

    /** 人类可读，日志用；不参与任何比较。 */
    override fun toString(): String {
        if (isEmpty) return "空集"
        val parts = ArrayList<String>(4)
        if (contains(Generations.GSM)) parts += "GSM"
        if (contains(Generations.UMTS)) parts += "UMTS"
        if (contains(Generations.LTE)) parts += "LTE"
        if (contains(Generations.NR)) parts += "NR"
        if (contains(Generations.CDMA)) parts += "CDMA"
        return parts.joinToString("+")
    }
}

/** 五个世代位，写表时用名字而不是裸数字。 */
object Generations {
    val GSM = GenerationMask(1)
    val UMTS = GenerationMask(2)
    val LTE = GenerationMask(4)
    val NR = GenerationMask(8)
    val CDMA = GenerationMask(16)
    val ALL: List<GenerationMask> = listOf(GSM, UMTS, LTE, NR, CDMA)
}

/**
 * `AT+ERAT?` 的回包：`+ERAT: <a>,<b>,<c>,<prefer>`；部分平台回裸包，例如 `7,0,6,0`。
 *
 * 字段语义没有可信公开来源，所以这里**不硬编码哪个字段是当前值**：[ratCandidates] 同时给出
 * 第 1 与第 3 字段，调用方必须在写入前后各读一次、以「发生变化的字段」为准。
 */
data class EratStatus(val rawFields: List<Int>) {

    val first: Int? get() = rawFields.getOrNull(0)

    /** GPRS attach 状态位，与制式无关，只留作日志。 */
    val gprs: Int? get() = rawFields.getOrNull(1)

    val third: Int? get() = rawFields.getOrNull(2)

    /** 偏好世代字段，128 常见于「优先 5G」。 */
    val prefer: Int? get() = rawFields.getOrNull(3)

    /** 看起来是当前生效 rat 的两个候选（字段语义无权威来源，两个都留）。 */
    val ratCandidates: List<Int>
        get() = listOfNotNull(first, third).distinct()

    /** 四字段形态才算完整；不足四个字段时只做日志，不参与判定。 */
    val isComplete: Boolean get() = rawFields.size >= 4

    fun logText(): String = "ERAT? " + rawFields.joinToString(",")
}

/**
 * `AT+ERAT=?` 的回包，例如 `+ERAT: (0-7,11,14)`。
 *
 * 它回答的是「这个 modem 认哪些 rat」，与 `?` 回答的「当前是什么」是两回事 ——
 * 拿 `?` 去判方言就是审核抓出来的那条错误。
 */
data class EratRange(val text: String) {

    /** 括号里逗号分隔的参数个数；匹配不到括号时按整串数，仍为空则 null。 */
    val paramCount: Int?
        get() {
            val group = PAREN.find(text)?.groupValues?.get(1) ?: text
            val count = group.split(',').count { it.isNotBlank() }
            return if (count == 0) null else count
        }

    fun logText(): String = "ERAT=? " + text

    private companion object {
        val PAREN = Regex("""\(([^)]*)\)""")
    }
}

/** AT 回包的纯解析，不碰任何 IO。 */
object AtProtocol {

    /**
     * 终止行判定。四类终止 token 缺一不可：只认 OK/ERROR 时，`+CME ERROR: 3` 会被当成普通回包，
     * 于是一直读到超时，还把调制解调器的拒绝原因埋进 partial 里。
     */
    fun terminatorOf(line: String): String? {
        val t = line.trim()
        return when {
            t == "OK" -> "OK"
            t == "ERROR" -> "ERROR"
            t.startsWith("+CME ERROR") -> t
            t.startsWith("+CMS ERROR") -> t
            else -> null
        }
    }

    fun isFailureTerminator(token: String): Boolean = token != "OK"

    /**
     * 剥掉可选前缀（`+ERAT:` / `+E5GOPT:` 等）。
     *
     * 不能假定前缀一定存在：CPE 实测回包是裸的 `7,0,6,0`，不带 `+ERAT:`。
     * `substringAfter` 找不到分隔符时原样返回，正好覆盖这两种形态。
     */
    fun payloadOf(line: String): String = line.substringAfter(':', line).trim()

    fun fieldsOf(payload: String): List<String> =
        payload.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** `AT+ERAT?`：所有回包行里第一条能完整解析成整数的行。 */
    fun parseEratStatus(reply: AtReply): EratStatus? {
        val lines = when (reply) {
            is AtReply.Ok -> reply.lines
            is AtReply.Error -> reply.lines
            else -> return null
        }
        for (line in lines) {
            val payload = payloadOf(line)
            if (payload.isEmpty()) continue
            val parsed = fieldsOf(payload).map { it.toIntOrNull() }
            if (parsed.isEmpty() || parsed.any { it == null }) continue
            return EratStatus(parsed.filterNotNull())
        }
        return null
    }

    /** `AT+ERAT=?`：取第一条非空回包行。 */
    fun parseEratRange(reply: AtReply): EratRange? {
        val lines = when (reply) {
            is AtReply.Ok -> reply.lines
            is AtReply.Error -> reply.lines
            else -> return null
        }
        val line = lines.firstOrNull { it.isNotBlank() } ?: return null
        val payload = payloadOf(line)
        if (payload.isEmpty()) return null
        return EratRange(payload)
    }
}