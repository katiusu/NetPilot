package com.katiusu.netpilot.core.monitor

/**
 * 一次判定的结果，带上人类可读的原因，便于直接写进运行日志。
 *
 * [fake] 的语义是「**是否需要降级**」——弱信号也置 `true`（而不是「是否为假满格」），
 * 这样引擎与 UI 的现有判断不用为新增的成因分叉；用 [reason] 区分是哪种成因。
 */
data class FakeJudgement(
    val fake: Boolean,
    val isStrong: Boolean,
    val reasons: List<String>,
    val detail: String,
    val reason: DowngradeReason = DowngradeReason.NONE,
)

/**
 * 网络质量降级判定，两条互相独立的规则：
 *
 * 1. **假满格**：对应 Network_Enhance `scripts/common.sh:924-990` 的 `se_detect_fake_5g()`。
 *    先要求信号达到「强」门槛（RSRP 严格大于阈值），再在三个条件里取「或」：
 *    Ping 严格大于阈值、SINR 严格小于阈值、Ping 完全失败（原脚本里最后这个分支
 *    事实上不可达，见 [DowngradeThresholds.downgradeOnPingFail]）。
 *    1.4.0 起额外要求**当前驻留在 5G / 5G+ 上**
 *    （[DowngradeThresholds.fakeFullBarOnNrOnly]，默认开）：原脚本跑在 5G 手机上、
 *    默认「信号强就是 5G」，本工程不能沿用这个假设。
 * 2. **信号过差**：RSRP 低于弱信号门槛时也降级 —— 弱 5G 不如稳 4G。
 *    这条是本工程新增的，原脚本没有。
 *
 * 门槛方向都是**严格**比较，与原脚本一致：RSRP = -85 不算强，Ping = 300 不算慢，
 * SINR = 0 不算差。
 */
object FakeSignalDetector {

    fun judge(snapshot: SignalSnapshot, thresholds: DowngradeThresholds): FakeJudgement {
        val rsrpText = snapshot.rsrp?.toString() ?: "未知"
        val isStrong = snapshot.isStrongSignal(thresholds.rsrpThreshold)
        // 当前在 Wi-Fi 且没有驻留蜂窝时，蜂窝制式不影响用户实际体验，判定没有意义。
        // 注意这里只是「不判定」，采样本身照常做（界面仍要显示 RSRP/Ping/SINR）。
        if (snapshot.isWifi && snapshot.rawNetworkType == 0) {
            return FakeJudgement(
                fake = false,
                isStrong = isStrong,
                reasons = emptyList(),
                detail = "当前在 Wi-Fi 且未驻留蜂窝，跳过降级判定",
            )
        }

        if (isStrong) {
            // 假满格的适用范围：只有 5G / 5G+ 才谈得上「信号格满但跑不动」。
            // 驻留在 4G / 3G / 2G 时同样的 Ping / SINR 读数不是「5G 假满格」的证据，
            // 而且降级目标本身就是 4G —— 写下去射频侧什么都不会变，只会把 5G 门关死；
            // 更糟的是降级后判据依然成立，恢复计数永远涨不上去，设备被永久锁在 4G。
            // 开关见 [DowngradeThresholds.fakeFullBarOnNrOnly]，因果链见
            // docs/POWER_REPORT.md §7。
            val nrOnlyBlocked = thresholds.fakeFullBarOnNrOnly && !snapshot.isOnNr()
            val reasons = ArrayList<String>(3)
            if (!nrOnlyBlocked) {
                snapshot.pingMs?.let { ping ->
                    if (ping > thresholds.pingThresholdMs) {
                        reasons += "Ping ${ping}ms > ${thresholds.pingThresholdMs}ms"
                    }
                }
                snapshot.sinr?.let { sinr ->
                    if (sinr < thresholds.sinrThreshold) {
                        reasons += "SINR ${sinr}dB < ${thresholds.sinrThreshold}dB"
                    }
                }
                if (snapshot.pingMs == null && thresholds.downgradeOnPingFail) {
                    reasons += "Ping 失败（无响应）"
                }
            }
            if (reasons.isNotEmpty()) {
                return FakeJudgement(
                    fake = true,
                    isStrong = true,
                    reasons = reasons,
                    // 制式写进 detail（方案 D）：用户看到「一直触发假满格」时，
                    // 第一件要回答的事就是「当时到底在 4G 还是 5G」。
                    detail = "判定为假满格（${snapshot.networkType}）：RSRP=$rsrpText dBm 满格但 " +
                        reasons.joinToString("；"),
                    reason = DowngradeReason.FAKE_FULL_BAR,
                )
            }
            val pingText = snapshot.pingMs?.let { "${it}ms" }
                ?: snapshot.pingError?.let { "失败（$it）" }
                ?: "失败"
            val sinrText = snapshot.sinr?.let { "${it}dB" } ?: "未知"
            return FakeJudgement(
                fake = false,
                isStrong = true,
                reasons = emptyList(),
                detail = if (nrOnlyBlocked) {
                    // 必须说清「没判」而不是「判了正常」：读数仍是强的、Ping 也照样显示，
                    // 差别只是本轮不拿它当降级依据。
                    "当前是 ${snapshot.networkType}，假满格只在 5G / 5G+ 上判定，" +
                        "本轮不检查 Ping 与 SINR（RSRP=$rsrpText dBm、Ping=$pingText、SINR=$sinrText）"
                } else {
                    "信号强且质量正常：RSRP=$rsrpText dBm、Ping=$pingText、SINR=$sinrText"
                },
            )
        }

        // 信号过差：必须真的读到 RSRP 才算 —— 「读不到」不等于「信号差」。
        // 这与「读不到不能当成强信号」是同一条原则：权限缺失 / 未插卡 / 读数为
        // UNKNOWN 时会得到 RSRP=null，若把它当成弱信号，监控就会无休止地把制式
        // 写成 4G，还会掩盖真正的读不到问题。
        val rsrp = snapshot.rsrp
        if (thresholds.downgradeOnWeakSignal && rsrp != null && rsrp < thresholds.weakRsrpThreshold) {
            return FakeJudgement(
                fake = true,
                isStrong = false,
                reasons = listOf("RSRP ${rsrp}dBm < ${thresholds.weakRsrpThreshold}dBm"),
                detail = "判定为信号过差（${snapshot.networkType}）：RSRP=$rsrpText dBm 低于 " +
                    "${thresholds.weakRsrpThreshold} dBm，" +
                    "弱 5G 不如稳 4G",
                reason = DowngradeReason.WEAK_SIGNAL,
            )
        }

        return FakeJudgement(
            fake = false,
            isStrong = false,
            reasons = emptyList(),
            detail = if (rsrp == null) {
                "RSRP 读不到（未知），不判定降级"
            } else {
                "RSRP=$rsrpText dBm 未达强信号门槛(${thresholds.rsrpThreshold} dBm)、" +
                    "也不低于弱信号门槛(${thresholds.weakRsrpThreshold} dBm)，不判定降级"
            },
        )
    }
}
