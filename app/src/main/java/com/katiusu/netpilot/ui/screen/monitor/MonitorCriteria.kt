package com.katiusu.netpilot.ui.screen.monitor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.monitor.DowngradeThresholds
import com.katiusu.netpilot.core.monitor.SinrUnavailableReason
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「判定标准（当前生效值）」整块内容，以及监控页共用的几行说明文本工具。
 *
 * 单独成文件的原因：这块内容全部由 [DowngradeThresholds] 现场拼出（用户在「功能」页改一个数、
 * ConfigState 一变就触发重组，这里立刻跟着变，绝不写死），行数不少但和内页其他区块没有耦合；
 * 从 MonitorPage.kt 拆出来可以让那个文件回到可读规模，同时不引入任何跨包依赖。
 */
@Composable
internal fun MonitorCriteriaLines(thresholds: DowngradeThresholds) {
    MonitorNoteLine(stringResource(R.string.q_criteria_hint))
    MonitorNoteLine(
        text = stringResource(
            R.string.q_criteria_full_bar_line,
            thresholds.rsrpThreshold,
            thresholds.pingThresholdMs,
            thresholds.sinrThreshold,
            if (thresholds.downgradeOnPingFail) {
                stringResource(R.string.q_criteria_full_bar_ping_fail)
            } else {
                ""
            },
            // 制式门控是判定前提的一部分，必须写在规则主句里：
            // 只看后面的 Ping / SINR 会以为它在任何制式下都生效。
            if (thresholds.fakeFullBarOnNrOnly) {
                stringResource(R.string.q_criteria_full_bar_nr_only)
            } else {
                ""
            },
        ),
        emphasized = true,
    )
    MonitorNoteLine(
        text = stringResource(
            R.string.q_criteria_full_bar_rule,
            thresholds.rsrpThreshold,
            thresholds.pingThresholdMs,
            thresholds.sinrThreshold,
        ),
    )
    // 只在门控开着时解释一次「为什么限 5G / 5G+」：关掉后这条限制不存在，
    // 再解释就会变成误导。
    if (thresholds.fakeFullBarOnNrOnly) {
        MonitorNoteLine(text = stringResource(R.string.q_criteria_nr_only_rule))
    }
    MonitorNoteLine(
        text = if (thresholds.downgradeOnWeakSignal) {
            stringResource(
                R.string.q_criteria_weak_line,
                thresholds.weakRsrpThreshold,
            )
        } else {
            stringResource(R.string.q_criteria_weak_off_line)
        },
        emphasized = true,
    )
    if (thresholds.downgradeOnWeakSignal) {
        MonitorNoteLine(
            text = stringResource(
                R.string.q_criteria_weak_rule,
                thresholds.weakRsrpThreshold,
            ),
        )
    }
    MonitorNoteLine(
        text = stringResource(R.string.q_criteria_rsrp_order_hint),
    )
    MonitorNoteLine(
        text = stringResource(R.string.q_criteria_none_line),
        emphasized = true,
    )
    MonitorNoteLine(
        text = stringResource(
            R.string.q_criteria_recovery_rule,
            thresholds.recoveryCount,
            durationText(thresholds.cooldownSec.toLong()),
        ),
    )
}

/**
 * 整行说明文本。
 *
 * Miuix 0.9.4 的 [BasicComponent] 没有 maxLines，长句会被裁掉；
 * 判定标准、SINR 读不到的原因、Ping 的完整异常都可能很长，所以一律用
 * [MiuixText] 单独换行渲染。[emphasized] 用于规则主句，比脚注更显眼一点。
 */
@Composable
internal fun MonitorNoteLine(text: String, emphasized: Boolean = false) {
    MiuixText(
        text = text,
        color = if (emphasized) {
            MiuixTheme.colorScheme.onSurface
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        style = if (emphasized) MiuixTheme.textStyles.body2 else MiuixTheme.textStyles.footnote2,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
    )
}

/** [SinrUnavailableReason] → 用户可读的原因文案。 */
@StringRes
internal fun sinrReasonRes(reason: SinrUnavailableReason): Int = when (reason) {
    SinrUnavailableReason.NO_CELLULAR -> R.string.q_sinr_reason_no_cellular
    SinrUnavailableReason.MISSING_PHONE_STATE -> R.string.q_sinr_reason_no_phone_state
    SinrUnavailableReason.MISSING_FINE_LOCATION -> R.string.q_sinr_reason_no_fine_location
    SinrUnavailableReason.LOCATION_SERVICES_OFF -> R.string.q_sinr_reason_location_off
    SinrUnavailableReason.LTE_RSSNR_UNAVAILABLE -> R.string.q_sinr_reason_lte_rssnr_na
    SinrUnavailableReason.NR_SS_SINR_UNAVAILABLE -> R.string.q_sinr_reason_nr_ss_sinr_na
    SinrUnavailableReason.NOT_REPORTED -> R.string.q_sinr_reason_not_reported
}

/** 秒数 → 「x 小时 y 分」/「x 分 y 秒」/「x 秒」，按量级选最合适的粒度。 */
@Composable
internal fun durationText(totalSec: Long): String {
    val sec = totalSec.coerceAtLeast(0L)
    return when {
        sec >= 3600L -> stringResource(
            R.string.monitor_value_hours,
            sec / 3600L,
            (sec % 3600L) / 60L,
        )
        sec >= 60L -> stringResource(R.string.monitor_value_minutes, sec / 60L, sec % 60L)
        else -> stringResource(R.string.monitor_value_seconds, sec)
    }
}
