package com.katiusu.netpilot.core.mode

import java.lang.reflect.Field

/**
 * Converts between the RIL preferred-network-mode constants (0..33, the values taken by
 * `ITelephony.setPreferredNetworkType`) and the `NETWORK_TYPE_BITMASK_*` unions that
 * `setAllowedNetworkTypesForReason` wants on Android 11+.
 *
 * The two directions use deliberately different machinery:
 * - forward: every mode is one row of a radio-family table, and a row is folded into bits;
 * - backward: exact rows come from an index built once, and anything else is decided by a
 *   priority ladder over the radio families actually present in the bitmask.
 *
 * [platform] reads the bitmask constants off `android.telephony.TelephonyManager`
 * reflectively, substituting the AOSP value for any field an OEM build leaves out.
 * [aosp] is assembled from the AOSP values alone, so it also runs off-device.
 */
class NetworkModeBitmaskMapper(
    private val gsm: Long,
    private val wcdma: Long,
    private val cdma: Long,
    private val evdo: Long,
    private val lte: Long,
    private val tdscdma: Long,
    private val nr: Long,
) {

    /**
     * The radio families a mode can select, in the order the constructor takes them.
     * [mask] gives each family its own bit inside a [MODE_FAMILIES] row.
     */
    private enum class Family {
        GSM, WCDMA, CDMA, EVDO, LTE, TDSCDMA, NR;

        val mask: Int get() = 1 shl ordinal
    }

    /**
     * Constructor arguments re-ordered by [Family], so expanding a family row is a loop
     * over set bits rather than one hand-written union expression per mode.
     */
    private val familyBits: LongArray = Family.entries.map { family ->
        when (family) {
            Family.GSM -> gsm
            Family.WCDMA -> wcdma
            Family.CDMA -> cdma
            Family.EVDO -> evdo
            Family.LTE -> lte
            Family.TDSCDMA -> tdscdma
            Family.NR -> nr
        }
    }.toLongArray()

    /**
     * RIL mode -> bitmask；**本机映射表里没有这个模式时返回 null**。
     *
     * 为什么不再「表外一律返回全部网络类型」（1.5.0 的行为变更）：旧写法会把一个算不出掩码的
     * 模式写成 `ALL_NETWORK_TYPES`，也就是**把这张卡放开到所有制式**。于是「锁 5G」这样的请求
     * 可能变成「不限制任何制式」，方向与用户意图完全相反，而且没有任何提示。现在把
     * 「这个模式我算不出掩码」如实交回调用方，由它拒绝写入并把事实记进日志。
     */
    fun toBitmask(networkMode: Int): Long? =
        MODE_FAMILIES.getOrNull(networkMode)?.let { expand(it) }

    /** Unites the bitmask constants of every family marked in [families]. */
    private fun expand(families: Int): Long {
        var pending = families
        var bits = 0L
        while (pending != 0) {
            bits = bits or familyBits[Integer.numberOfTrailingZeros(pending)]
            pending = pending and (pending - 1) // drop the lowest set bit, keep going
        }
        return bits
    }

    /**
     * Bitmask -> lowest RIL mode producing it, built on first use.
     *
     * The forward table is not injective: mode 3 repeats mode 0 exactly, so the first row
     * claiming a bitmask has to keep it, which is also how a modem reports the value back.
     * No other pair collides.
     */
    private val exactModes: Map<Long, Int> by lazy {
        val found = HashMap<Long, Int>(MODE_FAMILIES.size * 2)
        for (mode in MODE_FAMILIES.indices) {
            val bits = expand(MODE_FAMILIES[mode])
            if (!found.containsKey(bits)) found[bits] = mode
        }
        found
    }

    /**
     * Inverse of [toBitmask], best effort.
     *
     * An exact row wins first. Modems routinely report bits outside the mode table, so
     * otherwise the answer is the first rung of [DEGRADE_LADDER] whose families are all
     * present; a bitmask carrying none of them (or nothing at all) reads back as mode 0.
     */
    fun toNetworkMode(bitmask: Long): Int {
        exactModes[bitmask]?.let { return it }

        val present = familiesIn(bitmask)
        for ((required, mode) in DEGRADE_LADDER) {
            if ((present and required) == required) return mode
        }
        return 0
    }

    /** Family bits for every family contributing at least one bit to [bitmask]. */
    private fun familiesIn(bitmask: Long): Int {
        var present = 0
        for (index in familyBits.indices) {
            if ((bitmask and familyBits[index]) != 0L) {
                present = present or Family.entries[index].mask
            }
        }
        return present
    }

    companion object {
        /** Highest RIL mode the family table defines. */
        const val MAX_NETWORK_MODE = 33

        /**
         * 表内所有模式能产生的位并集（31 位）。
         *
         * 1.5.0 起**不再**作为「表外模式的返回值」使用（见 [toBitmask]）；保留它是为了给
         * 「全部网络类型」这个位掩码一个有名有据的常量，诊断与文档都要引用它。
         */
        const val ALL_NETWORK_TYPES = (1L shl 31) - 1

        private const val TELEPHONY_MANAGER = "android.telephony.TelephonyManager"

        // One set bit per radio family, aligned with Family.mask.
        private val GSM_SET = Family.GSM.mask
        private val WCDMA_SET = Family.WCDMA.mask
        private val CDMA_SET = Family.CDMA.mask
        private val EVDO_SET = Family.EVDO.mask
        private val LTE_SET = Family.LTE.mask
        private val TDSCDMA_SET = Family.TDSCDMA.mask
        private val NR_SET = Family.NR.mask

        /**
         * Radio families per RIL mode, indexed by the mode number: a row is the set of
         * technologies that mode lets the modem pick from. Storing the palette as data
         * (instead of one union of bitmask fields per mode) lets both directions share it
         * and keeps the China-specific TD-SCDMA rows easy to eyeball. Families inside a
         * row are listed in ascending order: GSM, WCDMA, CDMA, EVDO, LTE, TD-SCDMA, NR.
         */
        private val MODE_FAMILIES: IntArray = intArrayOf(
            /*  0 WCDMA_PREF                      */ GSM_SET or WCDMA_SET,
            /*  1 GSM_ONLY                        */ GSM_SET,
            /*  2 WCDMA_ONLY                      */ WCDMA_SET,
            /*  3 GSM_UMTS                        */ GSM_SET or WCDMA_SET,
            /*  4 CDMA                            */ CDMA_SET or EVDO_SET,
            /*  5 CDMA_NO_EVDO                    */ CDMA_SET,
            /*  6 EVDO_NO_CDMA                    */ EVDO_SET,
            /*  7 GLOBAL                          */ GSM_SET or WCDMA_SET or CDMA_SET or EVDO_SET,
            /*  8 LTE_CDMA_EVDO                   */ CDMA_SET or EVDO_SET or LTE_SET,
            /*  9 LTE_GSM_WCDMA                   */ GSM_SET or WCDMA_SET or LTE_SET,
            /* 10 LTE_CDMA_EVDO_GSM_WCDMA         */ GSM_SET or WCDMA_SET or CDMA_SET or EVDO_SET or LTE_SET,
            /* 11 LTE_ONLY                        */ LTE_SET,
            /* 12 LTE_WCDMA                       */ WCDMA_SET or LTE_SET,
            /* 13 TDSCDMA_ONLY                    */ TDSCDMA_SET,
            /* 14 TDSCDMA_WCDMA                   */ WCDMA_SET or TDSCDMA_SET,
            /* 15 LTE_TDSCDMA                     */ LTE_SET or TDSCDMA_SET,
            /* 16 TDSCDMA_GSM                     */ GSM_SET or TDSCDMA_SET,
            /* 17 LTE_TDSCDMA_GSM                 */ GSM_SET or LTE_SET or TDSCDMA_SET,
            /* 18 TDSCDMA_GSM_WCDMA               */ GSM_SET or WCDMA_SET or TDSCDMA_SET,
            /* 19 LTE_TDSCDMA_WCDMA               */ WCDMA_SET or LTE_SET or TDSCDMA_SET,
            /* 20 LTE_TDSCDMA_GSM_WCDMA           */ GSM_SET or WCDMA_SET or LTE_SET or TDSCDMA_SET,
            /* 21 TDSCDMA_CDMA_EVDO_GSM_WCDMA     */ GSM_SET or WCDMA_SET or CDMA_SET or EVDO_SET or TDSCDMA_SET,
            /* 22 LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA */ GSM_SET or WCDMA_SET or CDMA_SET or EVDO_SET or LTE_SET or TDSCDMA_SET,
            /* 23 NR_ONLY                         */ NR_SET,
            /* 24 NR_LTE                          */ LTE_SET or NR_SET,
            /* 25 NR_LTE_CDMA_EVDO                */ CDMA_SET or EVDO_SET or LTE_SET or NR_SET,
            /* 26 NR_LTE_GSM_WCDMA                */ GSM_SET or WCDMA_SET or LTE_SET or NR_SET,
            /* 27 NR_LTE_CDMA_EVDO_GSM_WCDMA      */ GSM_SET or WCDMA_SET or CDMA_SET or EVDO_SET or LTE_SET or NR_SET,
            /* 28 NR_LTE_WCDMA                    */ WCDMA_SET or LTE_SET or NR_SET,
            /* 29 NR_LTE_TDSCDMA                  */ LTE_SET or TDSCDMA_SET or NR_SET,
            /* 30 NR_LTE_TDSCDMA_GSM              */ GSM_SET or LTE_SET or TDSCDMA_SET or NR_SET,
            /* 31 NR_LTE_TDSCDMA_WCDMA            */ WCDMA_SET or LTE_SET or TDSCDMA_SET or NR_SET,
            /* 32 NR_LTE_TDSCDMA_GSM_WCDMA        */ GSM_SET or WCDMA_SET or LTE_SET or TDSCDMA_SET or NR_SET,
            /* 33 NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA */ GSM_SET or WCDMA_SET or CDMA_SET or EVDO_SET or LTE_SET or TDSCDMA_SET or NR_SET,
        )

        /**
         * Fallback order for bitmasks matching no row. An entry fires once every family in
         * its mask is present, so the list doubles as the "best technology left" ranking.
         */
        private val DEGRADE_LADDER: List<Pair<Int, Int>> = listOf(
            (NR_SET or LTE_SET) to 24,
            NR_SET to 23,
            LTE_SET to 11,
            TDSCDMA_SET to 13,
            WCDMA_SET to 2,
            GSM_SET to 1,
            (CDMA_SET or EVDO_SET) to 4,
            CDMA_SET to 5,
            EVDO_SET to 6,
        )

        /**
         * `NETWORK_TYPE_*` ids behind the families above, keyed by the suffix of the
         * `NETWORK_TYPE_BITMASK_*` field the platform exposes. AOSP derives each bitmask as
         * `1 shl (id - 1)`, so only the ids are stored and the shift is computed on demand.
         */
        private val AOSP_TYPE_IDS: Map<String, Int> = mapOf(
            "GPRS" to 1, "EDGE" to 2, "UMTS" to 3, "CDMA" to 4, "EVDO_0" to 5,
            "EVDO_A" to 6, "1xRTT" to 7, "HSDPA" to 8, "HSUPA" to 9, "HSPA" to 10,
            "EVDO_B" to 12, "LTE" to 13, "HSPAP" to 15, "GSM" to 16,
            "TD_SCDMA" to 17, "LTE_CA" to 19, "NR" to 20,
        )

        /** AOSP bitmask for one `NETWORK_TYPE_*` suffix. */
        private fun aospBit(suffix: String): Long = 1L shl (AOSP_TYPE_IDS.getValue(suffix) - 1)

        /** AOSP-only union for one family. */
        private fun aospFamily(vararg suffixes: String): Long =
            suffixes.fold(0L) { union, suffix -> union or aospBit(suffix) }

        /**
         * Platform union for one family: the reflective constant where that field exists,
         * the AOSP bit otherwise. The fallback is per field, not per family.
         */
        private fun platformFamily(vararg suffixes: String): Long =
            suffixes.fold(0L) { union, suffix ->
                val constant = staticField(TELEPHONY_MANAGER, "NETWORK_TYPE_BITMASK_$suffix")
                union or (constant?.getLong(null) ?: aospBit(suffix))
            }

        /** AOSP constants only: no reflection, so it is usable off-device too. */
        val aosp = NetworkModeBitmaskMapper(
            gsm = aospFamily("GSM", "GPRS", "EDGE"),
            wcdma = aospFamily("UMTS", "HSDPA", "HSUPA", "HSPA", "HSPAP"),
            cdma = aospFamily("CDMA", "1xRTT"),
            evdo = aospFamily("EVDO_0", "EVDO_A", "EVDO_B"),
            lte = aospFamily("LTE", "LTE_CA"),
            tdscdma = aospFamily("TD_SCDMA"),
            nr = aospFamily("NR"),
        )

        /** Mapper built from the running platform's constants, AOSP fallback per field. */
        val platform: NetworkModeBitmaskMapper by lazy {
            NetworkModeBitmaskMapper(
                gsm = platformFamily("GSM", "GPRS", "EDGE"),
                wcdma = platformFamily("UMTS", "HSDPA", "HSUPA", "HSPA", "HSPAP"),
                cdma = platformFamily("CDMA", "1xRTT"),
                evdo = platformFamily("EVDO_0", "EVDO_A", "EVDO_B"),
                lte = platformFamily("LTE", "LTE_CA"),
                tdscdma = platformFamily("TD_SCDMA"),
                nr = platformFamily("NR"),
            )
        }

        /** `TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER`, 0 where the field is absent. */
        val reasonUser: Int by lazy {
            staticField(TELEPHONY_MANAGER, "ALLOWED_NETWORK_TYPES_REASON_USER")?.getInt(null) ?: 0
        }

        /**
         * Hidden static field lookup. A missing class or field (OEM builds, plain JVMs)
         * yields null so the caller can substitute a fallback instead of crashing.
         */
        private fun staticField(className: String, fieldName: String): Field? {
            val owner = runCatching { Class.forName(className) }.getOrNull() ?: return null
            val field = runCatching { owner.getDeclaredField(fieldName) }.getOrNull() ?: return null
            return field.apply { isAccessible = true }
        }
    }
}
