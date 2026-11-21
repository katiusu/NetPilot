package io.github.katiusu.netpilot.core.radio

/** 基带所属厂商：判断的是调制解调器归谁家管，不是整机品牌。 */
enum class BasebandVendor { QUALCOMM, MEDIATEK, EXYNOS, UNISOC, UNKNOWN }

/**
 * 判定置信度。
 *
 * 只读探测拿不到 modem 自报的厂商信息，结论必然带「证据够不够硬」这一维，
 * 界面据此决定能不能说「已确认」。
 */
enum class Confidence { CONFIRMED, LIKELY, UNKNOWN }

/**
 * 本版对基带能做的事。
 *
 * 当前是纯只读版本，capabilities 恒为空集；枚举先落下来是为了让上层「能不能改制式」
 * 这类判断有一个稳定类型可用，而不是以后再加一堆 Boolean。
 */
enum class RadioCapability { SET_MODE, READ_MODE, SET_BAND, SET_SA_NSA, DUAL_SIM_SLOT }

/**
 * 一次基带探测的结论。
 *
 * @property evidence 人类可读的证据行，按探测顺序排列，界面与日志直接展示
 * @property atNodes 存在但未被打开的 AT 节点绝对路径
 * @property capabilities 本版恒为空集
 */
data class BasebandProfile(
    val vendor: BasebandVendor,
    val confidence: Confidence,
    val evidence: List<String>,
    val atNodes: List<String>,
    val capabilities: Set<RadioCapability>,
) {

    /** 厂商中文名，界面一行用。 */
    val vendorLabel: String get() = labelOf(vendor)

    /** 置信度中文名。 */
    val confidenceLabel: String
        get() = when (confidence) {
            Confidence.CONFIRMED -> "已确认"
            Confidence.LIKELY -> "较可能"
            Confidence.UNKNOWN -> "无结论"
        }

    /**
     * 命中的通道名（服务名 + AT 节点基名）。
     *
     * phoneEx 是服务不是节点，数据类里没有它的字段，于是从证据行反查。
     * 为什么不加字段：这个数据类要序列化进配置，为纯展示多一个字段就得为它维护兼容性，
     * 而证据行本来就要写它。
     */
    val channels: List<String>
        get() = buildList {
            if (evidence.any { line -> line.contains(PHONE_EX_SERVICE) }) add(PHONE_EX_SERVICE)
            atNodes.forEach { path -> add(path.substringAfterLast('/')) }
        }

    /**
     * 设置页「系统兼容性」卡片里的那一行。
     *
     * UNKNOWN 时只说没有结论：没有结论时通道与置信度都是空的，拼出来会变成
     * 「未知 ·  · 无结论」这种半成品，也容易被当成探测成功。
     */
    fun summaryLine(): String {
        if (vendor == BasebandVendor.UNKNOWN || confidence == Confidence.UNKNOWN) {
            return UNKNOWN_SUMMARY
        }
        val channel = channels.joinToString(" + ")
        return if (channel.isBlank()) {
            vendorLabel + " · " + confidenceLabel
        } else {
            vendorLabel + " · 通道：" + channel + " · " + confidenceLabel
        }
    }

    /** 日志页正文：属性值、命中路径、服务存在性，以及唯一没做的那件事。 */
    fun logText(): String {
        val sb = StringBuilder()
        sb.append("基带：").append(vendorLabel).append("（").append(confidenceLabel).append("）")
        val channel = channels.joinToString(" + ")
        if (channel.isBlank()) {
            sb.append("，未命中任何已知通道")
        } else {
            sb.append("，通道：").append(channel)
        }
        evidence.forEach { line -> sb.append('\n').append("· ").append(line) }
        sb.append('\n').append("· 本次未做 AT 探测：不打开任何 /dev 节点，避免抢占 RIL 独占的 modem 串口")
        sb.append('\n').append("· 能力集：")
        sb.append(if (capabilities.isEmpty()) "空集（只读版）" else capabilities.joinToString())
        return sb.toString()
    }

    companion object {

        /** 联发科的扩展服务名：它注册在 ServiceManager 里，基本可以断定 modem 侧走 MTK 的 RIL 扩展。 */
        const val PHONE_EX_SERVICE = "phoneEx"

        /** 无结论时的固定文案，界面靠它避免把「不知道」渲染成成功态。 */
        const val UNKNOWN_SUMMARY = "未知（本次探测无结论）"

        /** 厂商中文名。 */
        fun labelOf(vendor: BasebandVendor): String = when (vendor) {
            BasebandVendor.QUALCOMM -> "高通"
            BasebandVendor.MEDIATEK -> "联发科"
            BasebandVendor.EXYNOS -> "三星"
            BasebandVendor.UNISOC -> "紫光展锐"
            BasebandVendor.UNKNOWN -> "未知"
        }
    }
}
