package io.github.katiusu.netpilot.core.priv.at

import io.github.katiusu.netpilot.core.priv.at.Generations.CDMA
import io.github.katiusu.netpilot.core.priv.at.Generations.LTE
import io.github.katiusu.netpilot.core.priv.at.Generations.NR
import io.github.katiusu.netpilot.core.priv.at.Generations.UMTS

/** 一次映射的结果。[targetMask] 已剥掉 CDMA 位，是真正要写进 modem 的集合。 */
data class EratMapping(
    val rat: EratRat,
    val prefer: Int,
    val targetMask: GenerationMask,
    val originalMask: GenerationMask,
    /** 宽松映射：模式含 CDMA（被剥掉），或目标集合没有精确 rat、取了最小超集。 */
    val lenient: Boolean,
    /** 人话说明，直接进日志。 */
    val note: String,
)

/** [MtkEratTable.resolve] 的结论：命中的 rat 与是否精确。 */
data class EratResolution(val rat: EratRat, val exact: Boolean)

/**
 * 联发科 `AT+ERAT` 的三张表：rat ↔ 世代集合、prefer 推导、NetworkMode → 写入参数。
 *
 * 三条硬规则（都是审核改出来的，改动前请先看理由）：
 *
 * 1. **准入与回读分开判**。准入只要求「目标能映射到一个确定的 rat」（允许取超集、多开一档）；
 *    回读才要求 `readMask ⊇ wantedMask`。早期版本把「目标必须是当前集合的超集」当准入条件，
 *    结果降级目标 9（GSM+UMTS+LTE）永远是当前 26/27（含 NR）的子集，每次降级都被拒 ——
 *    整个自动降级在联发科上等于关掉。
 * 2. **CDMA 是忽略位，不是禁写族**。电信卡默认模式 27 含 CDMA，整族禁写会让「恢复默认」不可用；
 *    正确做法是剥掉 CDMA 位再查表（27 → 全制式 → rat 22），并在日志里标注宽松。
 * 3. **prefer 按集合最高世代推导**（G3→2、G4→4、G5→128）。字段语义无权威来源，这个推导是
 *    当前的合理猜测，日志里会写明；将来拿到真机证据再改这一处即可。
 */
object MtkEratTable {

    /**
     * 表 A 的反表：**可写**的世代集合 → rat。
     *
     * 只有这 11 个集合有确定的 rat。含 CDMA 的集合一个都不在这里 —— 不是遗漏：
     * MTK 上 CDMA 已无网，把 CDMA 位写进 modem 在部分平台直接回 ERROR，所以统一先剥 CDMA。
     */
    private val RAT_BY_MASK: Map<Int, Int> = mapOf(
        0b00001 to 0,   // 仅 GSM
        0b00010 to 1,   // 仅 UMTS
        0b00011 to 2,   // GSM + UMTS
        0b00100 to 3,   // 仅 LTE
        0b00101 to 4,   // GSM + LTE
        0b00110 to 5,   // UMTS + LTE
        0b00111 to 6,   // GSM + UMTS + LTE
        0b01000 to 15,  // 仅 NR
        0b01100 to 19,  // LTE + NR
        0b01110 to 21,  // UMTS + LTE + NR
        0b01111 to 22,  // 全制式
    )

    /**
     * 表 A 正表：rat → 世代集合，**只用于读回比较**。
     *
     * 这里包含含 CDMA 的 rat（7/8/10/11/12/14/26/30）：modem 当前可能是这些值，
     * 读不出来就没法判断「写进去没有」。写方向永远不查这张表。
     */
    private val MASK_BY_RAT: Map<Int, Int> = mapOf(
        0 to 0b00001, 1 to 0b00010, 2 to 0b00011, 3 to 0b00100,
        4 to 0b00101, 5 to 0b00110, 6 to 0b00111, 7 to 0b10000,
        8 to 0b10001, 10 to 0b10011, 11 to 0b10100, 12 to 0b10101,
        14 to 0b10111, 15 to 0b01000, 19 to 0b01100, 21 to 0b01110,
        22 to 0b01111, 26 to 0b11100, 30 to 0b11111,
    )

    /**
     * 表 C：NetworkMode 值 → 该模式实际打开的世代集合（**含** CDMA 位）。
     *
     * 逐条写死而不是按 `NetworkMode.gen` 推导：gen 是「最高代际」，21 的 gen 是 3 却含 CDMA，
     * 用 gen 推会把电信/广电的默认模式算错。这张表与 [io.github.katiusu.netpilot.core.mode.NetworkMode]
     * 的条目一一对应，新增制式时两边都要改。
     */
    private val MODE_MASK: Map<Int, Int> = mapOf(
        0 to 0b00011, 1 to 0b00001, 2 to 0b00010, 3 to 0b00011,
        4 to 0b10000, 5 to 0b10000, 6 to 0b10000, 7 to 0b10011,
        8 to 0b10100, 9 to 0b00111, 10 to 0b10111, 11 to 0b00100,
        12 to 0b00110, 13 to 0b00010, 14 to 0b00010, 15 to 0b00110,
        16 to 0b00011, 17 to 0b00111, 18 to 0b00011, 19 to 0b00111,
        20 to 0b00111, 21 to 0b10011, 22 to 0b10111, 23 to 0b01000,
        24 to 0b01100, 25 to 0b11100, 26 to 0b01111, 27 to 0b11111,
        28 to 0b01110, 29 to 0b01110, 30 to 0b01111, 31 to 0b01110,
        32 to 0b01111, 33 to 0b11111,
    )

    private const val CDMA_BIT = 0b10000

    /** rat → 世代集合；表外 rat 返回 null（fail-closed）。 */
    fun maskOf(rat: EratRat): GenerationMask? {
        val bits = MASK_BY_RAT[rat.raw] ?: return null
        return GenerationMask(bits)
    }

    /**
     * 世代集合 → rat。
     *
     * 精确命中直接用；没有精确对应时取**最小超集**（多开一档，标 exact=false）；
     * 连超集都构造不出来（只可能是空集）返回 null。
     */
    fun resolve(mask: GenerationMask): EratResolution? {
        if (mask.isEmpty) return null
        RAT_BY_MASK[mask.bits]?.let { return EratResolution(EratRat(it), exact = true) }
        val superset = RAT_BY_MASK.keys
            .filter { key -> (key and mask.bits) == mask.bits }
            .minOrNull() ?: return null
        return EratResolution(EratRat(RAT_BY_MASK.getValue(superset)), exact = false)
    }

    /**
     * prefer 字段：集合最高世代。G3→2、G4→4、G5→128、仅 2G→0。
     *
     * 这是推测值，不是文档值（`+ERAT` 的字段语义没有公开来源）。放在一个函数里是为了
     * 将来拿到真机证据时只改这一处。
     */
    fun preferFor(mask: GenerationMask): Int = when {
        mask.contains(NR) -> 128
        mask.contains(LTE) -> 4
        mask.contains(UMTS) -> 2
        else -> 0
    }

    /**
     * NetworkMode 值 → 写入参数。返回 null 表示这个模式在联发科上不该写，
     * 调用方必须落回下一条策略，不能猜一个近似值。
     */
    fun mapNetworkMode(modeValue: Int): EratMapping? {
        val original = MODE_MASK[modeValue] ?: return null
        val hadCdma = (original and CDMA_BIT) != 0
        val stripped = GenerationMask(original and CDMA_BIT.inv())
        // 纯 CDMA / 纯 EVDO：剥完是空集，MTK 上没有可写的对应 rat。
        if (stripped.isEmpty) return null
        val resolution = resolve(stripped) ?: return null
        val lenient = !resolution.exact || hadCdma
        return EratMapping(
            rat = resolution.rat,
            prefer = preferFor(GenerationMask(original)),
            targetMask = stripped,
            originalMask = GenerationMask(original),
            lenient = lenient,
            note = buildNote(hadCdma, resolution.exact, stripped, resolution.rat),
        )
    }

    private fun buildNote(
        hadCdma: Boolean,
        exact: Boolean,
        target: GenerationMask,
        rat: EratRat,
    ): String {
        val parts = ArrayList<String>(2)
        if (hadCdma) parts += "模式含 CDMA，已剥掉（MTK 上 CDMA 无网）"
        if (!exact) parts += "目标集合无精确 rat，取最小超集 " + target
        parts += "映射为 " + rat
        return parts.joinToString("；")
    }

    /**
     * 回读比较：modem 当前集合是否**包含**目标集合。
     *
     * 只比较剥掉 CDMA 的部分：modem 回读里带 CDMA 位是正常现象，
     * 拿它当不一致会把「其实已经写对」判成失败。
     */
    fun covers(current: GenerationMask, wanted: GenerationMask): Boolean =
        current.without(CDMA).contains(wanted)

    /** 日志用：把 rat 渲成「ratN（GSM+LTE）」这种形式。 */
    fun describeRat(rat: EratRat): String {
        val mask = maskOf(rat) ?: return rat.toString() + "（表外，无法解读）"
        return rat.toString() + "（" + mask + "）"
    }
}