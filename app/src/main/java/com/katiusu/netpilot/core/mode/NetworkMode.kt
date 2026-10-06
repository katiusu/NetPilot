package com.katiusu.netpilot.core.mode

/**
 * 首选网络制式，取值即 RIL / `TelephonyManager.NETWORK_MODE_*` 的 0..33。
 *
 * 每个条目携带写入用的原始常量与两份展示信息：
 * - [value]：交给特权层的 RIL 常量。写入前由 [NetworkModeBitmaskMapper] 换算成
 *   `NETWORK_TYPE_BITMASK_*` 位掩码（Android 11+ 的 `setAllowedNetworkTypesForReason`）；
 *   Android 10 及以下直接走 `setPreferredNetworkType`；root 兜底则写
 *   `settings put global preferred_network_mode`。
 * - [label]：列表/日志展示用的中文名；[gen] 供 UI 分组与图标选择使用，不参与写入。
 *
 * @property value RIL 常量值（0..33）。
 * @property label 中文短名，供磁贴/列表直接展示。
 * @property gen 最高代际：5=含 NR、4=含 LTE、3=含 WCDMA/TD-SCDMA、2=仅 GSM/CDMA/EVDO。
 *   只用于 UI 分组与图标选择，不参与任何写入。
 */
enum class NetworkMode(val value: Int, val label: String, val gen: Int) {

    // 2G/3G 组合（尚无 LTE）---------------------------------------------------
    WCDMA_PREF(0, "3G/2G 自动", 3),
    GSM_ONLY(1, "仅 2G (GSM)", 2),
    WCDMA_ONLY(2, "仅 3G (WCDMA)", 3),
    /** 可选制式与 [WCDMA_PREF] 一致，位掩码因此重合，回读时会被归一到 0。 */
    GSM_UMTS(3, "3G/2G 自动", 3),
    CDMA(4, "CDMA 自动", 2),
    CDMA_NO_EVDO(5, "仅 CDMA (1x)", 2),
    EVDO_NO_CDMA(6, "仅 EVDO (CDMA)", 2),
    GLOBAL(7, "全球模式 3G/2G", 3),
    LTE_CDMA_EVDO(8, "4G/3G/2G 自动 (CDMA)", 4),

    // LTE 组合 --------------------------------------------------------------
    /** 位掩码为 LTE|GSM|WCDMA，不含 NR，所以代际记 4 而不是 5。 */
    LTE_GSM_WCDMA(9, "4G/3G/2G 自动", 4),
    LTE_CDMA_EVDO_GSM_WCDMA(10, "4G/3G/2G 自动 (全制式)", 4),
    LTE_ONLY(11, "仅 4G (LTE)", 4),
    LTE_WCDMA(12, "4G/3G 自动 (WCDMA)", 4),
    TDSCDMA_ONLY(13, "仅 3G (TD-SCDMA)", 3),
    TDSCDMA_WCDMA(14, "3G 自动 (TD/WCDMA)", 3),
    LTE_TDSCDMA(15, "4G/3G 自动 (LTE/TD)", 4),
    TDSCDMA_GSM(16, "3G/2G 自动 (TD/GSM)", 3),
    LTE_TDSCDMA_GSM(17, "4G/3G/2G 自动 (TD/GSM)", 4),
    TDSCDMA_GSM_WCDMA(18, "3G/2G 自动 (TD)", 3),
    LTE_TDSCDMA_WCDMA(19, "4G/3G 自动 (TD/WCDMA)", 4),
    LTE_TDSCDMA_GSM_WCDMA(20, "4G/3G/2G 自动 (TD)", 4),
    TDSCDMA_CDMA_EVDO_GSM_WCDMA(21, "3G/2G 自动 (全制式)", 3),
    LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA(22, "4G/3G/2G 自动 (全制式)", 4),

    // NR（5G）组合 ----------------------------------------------------------
    NR_ONLY(23, "仅 5G (NR)", 5),
    NR_LTE(24, "5G/4G 自动", 5),
    NR_LTE_CDMA_EVDO(25, "5G/4G/3G 自动 (CDMA)", 5),
    NR_LTE_GSM_WCDMA(26, "5G/4G/3G/2G 自动(联通)", 5),
    NR_LTE_CDMA_EVDO_GSM_WCDMA(27, "5G/4G/3G/2G 自动(电信)", 5),
    NR_LTE_WCDMA(28, "5G/4G/3G 自动 (WCDMA)", 5),
    NR_LTE_TDSCDMA(29, "5G/4G/3G 自动 (TD)", 5),
    NR_LTE_TDSCDMA_GSM(30, "5G/4G/3G/2G 自动 (TD/GSM)", 5),
    NR_LTE_TDSCDMA_WCDMA(31, "5G/4G/3G 自动 (TD/WCDMA)", 5),
    NR_LTE_TDSCDMA_GSM_WCDMA(32, "5G/4G/3G/2G 自动(移动)", 5),
    NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA(33, "5G/4G/3G/2G 自动(广电)", 5);

    /** 磁贴/通知栏用的超短名，等价于 [shortLabelOf]。 */
    val shortLabel: String get() = shortLabelOf(value)

    companion object {

        /** 运营商无法识别时使用的通用自动模式：NR|LTE|GSM|WCDMA（RIL 值 26）。 */
        private const val FALLBACK = 26

        /** 中国大陆 MCC；比较前会去掉前导零。 */
        private const val MCC_CHINA = "460"

        /** 代际给不出短名时的兜底文案。 */
        private const val SHORT_LABEL_LEGACY = "2G 自动"

        /** 取值反查表，建一次即可，避免每次调用线性扫描 [entries]。 */
        private val BY_VALUE: Map<Int, NetworkMode> = entries.associateBy { it.value }

        /** 网络质量自动降级的目标制式：4G 自动（LTE|GSM|WCDMA，不含 NR）。 */
        val DEFAULT_DOWNGRADE: NetworkMode = LTE_GSM_WCDMA

        /** 游戏模式/锁频的目标制式：仅 4G。 */
        val DEFAULT_LOCK_LTE: NetworkMode = LTE_ONLY

        /**
         * 无法由代际概括的短名。其余条目交给 [SHORT_LABEL_BY_GEN]，这样以后新增同代际
         * 条目时不必再补文案。
         */
        private val SHORT_LABEL_OVERRIDES: Map<NetworkMode, String> = mapOf(
            NR_ONLY to "仅 5G",
            LTE_ONLY to "仅 4G",
            WCDMA_ONLY to "仅 3G",
            GSM_ONLY to "仅 2G",
            TDSCDMA_ONLY to "仅 3G(TD)",
            CDMA_NO_EVDO to "仅 1x",
            EVDO_NO_CDMA to "仅 EVDO",
            CDMA to "CDMA 自动",
            GLOBAL to "全局自动",
        )

        /** 代际 → 归并短名。 */
        private val SHORT_LABEL_BY_GEN: Map<Int, String> = mapOf(
            5 to "5G 自动",
            4 to "4G 自动",
            3 to "3G 自动",
        )

        /** 中国大陆运营商（用于默认模式、日志与设置页展示）。 */
        enum class Carrier(val display: String) {
            CHINA_MOBILE("中国移动"),
            CHINA_UNICOM("中国联通"),
            CHINA_TELECOM("中国电信"),
            CHINA_BROADNET("中国广电"),
        }

        /** 一行一个运营商：识别用的 MNC 集合 + 该运营商的默认「全网通」模式。 */
        private data class OperatorEntry(
            val carrier: Carrier,
            val mncKeys: Set<String>,
            val mode: NetworkMode,
        )

        /**
         * 运营商默认模式对照表：键是**去掉前导零后的 MNC**，一行一个运营商，先命中的行胜出。
         *
         * 号段依据（1.5.0 重新核对）：以 MCC 460 的公开分配为准，拿五个互相独立的来源交叉验证 ——
         * musalbas/mcc-mnc-table、pbakondy/mcc-mnc-list、mcc-mnc.org 的 460 页、ITU-T E.212
         * 公报 OB 1280（2023）、以及运营商侧资料。ITU 那份只登记了 00/01/03/04 四条，
         * 粒度不足以定运营商，只当「这些号段确实存在」的下限核对用。
         *
         * | 运营商 | MCC+MNC | 去零后的键 |
         * | --- | --- | --- |
         * | 中国移动 | 46000 / 46002 / 46004 / 46007 / 46008 / 46020（铁通，2008 并入移动） | "" / 2 / 4 / 7 / 8 / 20 |
         * | 中国联通 | 46001 / 46006 / 46009 | 1 / 6 / 9 |
         * | 中国电信 | 46003 / 46005（CDMA 遗留）/ 46011 | 3 / 5 / 11 |
         * | 中国广电 | 46015 | 15 |
         *
         * 1.5.0 改了什么（都记在这里，因为这张表本质是「猜」，必须能追溯）：
         *  - **补 `5`（电信）**：46005 在三个来源里都在，旧表漏了它 —— 这张卡会落到兜底 26
         *    （联通档），这是本轮查出的第一处真错值。
         *  - **补 `20`（移动）**：46020 铁通，2008 年并入移动，来源里明确标注在用；旧表同样漏了。
         *  - **删 `10`（旧表当联通）与 `27`（旧表当电信）**：这两个号段在五个来源里一个都查不到。
         *    不存在的 MNC 永远不会命中，留着唯一的后果是让这张表看起来「有依据」；删掉之后万一
         *    真有，结果是落到兜底 26 —— 与「它本来就没有权威归属」一致，不算回归。
         *  - `15`（广电）旧表就对：46015 在 pbakondy 列表里是在用状态，与 192 号段 2022 年商用
         *    的事实吻合；注意 mcc-mnc.org 没收录它 —— 所以不能只看一个来源。
         */
        private val OPERATOR_DEFAULTS: List<OperatorEntry> = listOf(
            OperatorEntry(Carrier.CHINA_TELECOM, setOf("3", "5", "11"), NR_LTE_CDMA_EVDO_GSM_WCDMA),
            OperatorEntry(Carrier.CHINA_MOBILE, setOf("", "2", "4", "7", "8", "20"), NR_LTE_TDSCDMA_GSM_WCDMA),
            OperatorEntry(Carrier.CHINA_UNICOM, setOf("1", "6", "9"), NR_LTE_GSM_WCDMA),
            OperatorEntry(Carrier.CHINA_BROADNET, setOf("15"), NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA),
        )

        /** 取值反查；0..33 之外返回 null。 */
        fun fromValue(value: Int): NetworkMode? = BY_VALUE[value]

        /**
         * 磁贴与快捷切换用的一组常用模式，顺序固定：
         * 仅 4G、4G 自动、仅 5G、移动/电信/广电 5G 自动、仅 2G、仅 3G。
         */
        val quickPresets: List<NetworkMode> = listOf(11, 9, 23, 26, 27, 32, 1, 2)
            .mapNotNull { fromValue(it) }

        /**
         * MCC 是否属于中国大陆。缺失也算国内：多数机型拿得到 MCC，拿不到时按国内处理更贴合本工程
         * 的用户分布；而境外卡一定有 MCC，不会因此被误判成国内卡。
         */
        private fun isDomestic(mcc: String?): Boolean =
            mcc == null || mcc.trim().trimStart('0') == MCC_CHINA

        /** MNC 归一化：去空白、去前导零（`"00"` 与 `""` 因此等价，`"03"` 与 `"3"` 等价）。 */
        private fun mncKey(mnc: String?): String = mnc?.trim()?.trimStart('0').orEmpty()

        /** 命中运营商表；境外卡或表内没有的号段返回 null（不猜）。 */
        private fun operatorEntry(mcc: String?, mnc: String?): OperatorEntry? {
            if (!isDomestic(mcc)) return null
            val key = mncKey(mnc)
            return OPERATOR_DEFAULTS.firstOrNull { key in it.mncKeys }
        }

        /**
         * 识别 (mcc, mnc) 属于哪家国内运营商；识别不出（境外卡 / 未收录号段）返回 null。
         *
         * 为什么与 [carrierDefault] 分开：本函数只回答「是谁」，可以被日志与设置页直接展示，
         * 不关心它对应哪个制式；[carrierDefault] 才回答「该给它什么默认模式」。
         */
        fun carrierOf(mcc: String?, mnc: String?): Carrier? = operatorEntry(mcc, mnc)?.carrier

        /** 识别结果的展示名（「中国移动」…）；识别不出返回 null。 */
        fun carrierName(mcc: String?, mnc: String?): String? = carrierOf(mcc, mnc)?.display

        /**
         * 按 (mcc, mnc) 猜测运营商默认模式。
         *
         * 非国内卡、或国内号码段不在 [OPERATOR_DEFAULTS] 里时取 [FALLBACK]（模式 26 =
         * NR|LTE|GSM|WCDMA）—— 这是「不清楚就挑兼容性最好的自动模式」，不是「按运营商猜」。
         */
        fun carrierDefault(mcc: String?, mnc: String?): NetworkMode =
            operatorEntry(mcc, mnc)?.mode ?: BY_VALUE.getValue(FALLBACK)

        /**
         * 各代际的「运营商自动」取值（键 = [NetworkMode.gen]），只覆盖 4G/3G/2G。
         *
         * 5G 那一档不进这张表：它由 [OPERATOR_DEFAULTS] 提供，同一份数字写两遍迟早会不一致，
         * 而 5G 档是自动降级「恢复」真正写回的值，改错的代价最大。
         *
         * 数字怎么来的：把该运营商 5G 默认模式的位掩码**逐级去掉更高代际** —— 去掉 NR 得 4G 档、
         * 再去掉 LTE 得 3G 档、只剩 2G 得 2G 档。同一条「自动适配运营商」在四个代际组里因此指向
         * 同一张网的同一种组网方式，不会出现「5G 组认成联通、4G 组认成电信」这种事。
         *
         * | gen | 移动 | 联通 | 电信 | 广电 |
         * | --- | --- | --- | --- | --- |
         * | 5 | 32 | 26 | 27 | 33 |
         * | 4 | 20 | 9 | 10 | 22 |
         * | 3 | 18 | 0 | 21 | 21 |
         * | 2 | 1 | 1 | 4 | 1 |
         *
         * 广电没有自己的 2G/3G：3G 档取 21（含 CDMA/EVDO 的全制式）、2G 档取 1（GSM），都是
         * 「靠漫游能用的最小集合」，与它 5G 档取 33（全制式）的取舍一致。
         */
        private val CARRIER_AUTO_BY_GEN: Map<Int, Map<Carrier, NetworkMode>> = mapOf(
            4 to mapOf(
                Carrier.CHINA_MOBILE to LTE_TDSCDMA_GSM_WCDMA,
                Carrier.CHINA_UNICOM to LTE_GSM_WCDMA,
                Carrier.CHINA_TELECOM to LTE_CDMA_EVDO_GSM_WCDMA,
                Carrier.CHINA_BROADNET to LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA,
            ),
            3 to mapOf(
                Carrier.CHINA_MOBILE to TDSCDMA_GSM_WCDMA,
                Carrier.CHINA_UNICOM to WCDMA_PREF,
                Carrier.CHINA_TELECOM to TDSCDMA_CDMA_EVDO_GSM_WCDMA,
                Carrier.CHINA_BROADNET to TDSCDMA_CDMA_EVDO_GSM_WCDMA,
            ),
            2 to mapOf(
                Carrier.CHINA_MOBILE to GSM_ONLY,
                Carrier.CHINA_UNICOM to GSM_ONLY,
                Carrier.CHINA_TELECOM to CDMA,
                Carrier.CHINA_BROADNET to GSM_ONLY,
            ),
        )

        /** 识别不出运营商时各代际的通用档（4G/3G/2G；5G 由 [carrierDefault] 自己的兜底负责）。 */
        private val CARRIER_AUTO_FALLBACK_BY_GEN: Map<Int, NetworkMode> = mapOf(
            4 to LTE_GSM_WCDMA,
            3 to WCDMA_PREF,
            2 to GSM_ONLY,
        )

        /**
         * 按 (mcc, mnc) 取「该代际的运营商自动模式」，供界面里每个代际组的那一行合成项使用。
         *
         * 5G 档直接复用 [carrierDefault]：两者按设计必须一致，而后者是自动降级恢复写回的值，
         * 语义不能动。其余代际查 [CARRIER_AUTO_BY_GEN]；识别不出运营商（境外卡、未收录号段、
         * SIM 未就绪）时给该代际的通用档；代际本身不在表里则给 [FALLBACK]。
         */
        fun carrierAutoForGen(gen: Int, mcc: String?, mnc: String?): NetworkMode {
            if (gen >= 5) return carrierDefault(mcc, mnc)
            val table = CARRIER_AUTO_BY_GEN[gen] ?: return BY_VALUE.getValue(FALLBACK)
            return carrierOf(mcc, mnc)?.let { table[it] }
                ?: CARRIER_AUTO_FALLBACK_BY_GEN[gen]
                ?: BY_VALUE.getValue(FALLBACK)
        }

        /** 制式值 → 可读长名（列表/日志用）；未知值不抛异常，而是显式提示。 */
        fun labelOf(value: Int): String = BY_VALUE[value]?.label ?: "未知模式($value)"

        /** 制式值 → 超短名（磁贴/通知栏用）：先查显式短名，再按 [gen] 归并。 */
        fun shortLabelOf(value: Int): String {
            val mode = BY_VALUE[value] ?: return "未知"
            return SHORT_LABEL_OVERRIDES[mode]
                ?: SHORT_LABEL_BY_GEN[mode.gen]
                ?: SHORT_LABEL_LEGACY
        }
    }
}
