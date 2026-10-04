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

        /**
         * 运营商默认模式对照表：一行一个运营商，键是去掉前导零后的 MNC，先命中的行胜出。
         *
         * 号段取自 MCC 460 下的公开分配：电信 3/11/27，移动 2/4/7/8（原 00/02/04/07/08，
         * 其中 00 去零后是空串），联通 1/6/9/10，广电 15。
         */
        private val OPERATOR_DEFAULTS: List<Pair<Set<String>, NetworkMode>> = listOf(
            setOf("3", "11", "27") to NR_LTE_CDMA_EVDO_GSM_WCDMA,
            setOf("", "2", "4", "7", "8") to NR_LTE_TDSCDMA_GSM_WCDMA,
            setOf("1", "6", "9", "10") to NR_LTE_GSM_WCDMA,
            setOf("15") to NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA,
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
         * 按 (mcc, mnc) 猜测运营商默认模式。
         *
         * MCC 缺失或去前导零后为 460 才算国内卡；境外卡直接取 [FALLBACK]。国内卡把 MNC
         * 去前导零后在 [OPERATOR_DEFAULTS] 里找第一行命中，表内没有的组合同样取 [FALLBACK]。
         */
        fun carrierDefault(mcc: String?, mnc: String?): NetworkMode {
            val domestic = mcc == null || mcc.trim().trimStart('0') == MCC_CHINA
            if (!domestic) return BY_VALUE.getValue(FALLBACK)

            val mncKey = mnc?.trim()?.trimStart('0').orEmpty()
            return OPERATOR_DEFAULTS.firstOrNull { mncKey in it.first }?.second
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
