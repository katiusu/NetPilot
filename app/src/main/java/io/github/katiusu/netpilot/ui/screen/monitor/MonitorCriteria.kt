package io.github.katiusu.netpilot.ui.screen.monitor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.monitor.DowngradeThresholds
import io.github.katiusu.netpilot.core.monitor.SinrUnavailableReason
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
