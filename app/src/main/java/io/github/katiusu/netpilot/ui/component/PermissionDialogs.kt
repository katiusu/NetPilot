package io.github.katiusu.netpilot.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.PermissionGuide
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text as MiuixText


@Composable
fun PermissionRationaleDialog(
    items: List<PermissionGuide.Item>,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.perm_rationale_title),
        summary = stringResource(R.string.perm_rationale_summary),
        onDismissRequest = onCancel,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEach { item ->
                ReasonRow(
                    name = stringResource(item.nameRes),
                    reason = stringResource(item.reasonRes),
                )
            }
            ButtonRow(
                cancelText = stringResource(R.string.action_cancel),
                onCancel = onCancel,
                confirmText = stringResource(R.string.perm_action_continue),
                onConfirm = onContinue,
            )
        }
    }
}

@Composable
fun PermissionDeniedDialog(
    items: List<PermissionGuide.Item>,
    onOpenSettings: () -> Unit,
    onLater: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.perm_denied_title),
        summary = stringResource(R.string.perm_denied_summary),
        onDismissRequest = onLater,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                items.forEach { item ->
                    ReasonRow(
                        name = stringResource(item.nameRes),
                        reason = stringResource(item.reasonRes),
                    )
                }
            }
            ButtonRow(
                cancelText = stringResource(R.string.perm_action_later),
                onCancel = onLater,
                confirmText = stringResource(R.string.perm_action_go_settings),
                onConfirm = onOpenSettings,
            )
        }
    }
}

@Composable
fun FirstLaunchGuideDialog(
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: (() -> Unit)? = null,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.perm_first_launch_title),
        summary = stringResource(R.string.perm_first_launch_summary),
        onDismissRequest = onCancel,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ReasonRow(
                name = stringResource(R.string.perm_first_launch_autostart_name),
                reason = stringResource(R.string.perm_first_launch_autostart_reason),
            )
            ReasonRow(
                name = stringResource(R.string.perm_first_launch_battery_name),
                reason = stringResource(R.string.perm_first_launch_battery_reason),
            )
            MiuixText(
                text = stringResource(R.string.perm_first_launch_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (onRequestIgnoreBatteryOptimizations != null) {
                TextButton(
                    text = stringResource(R.string.perm_battery_ignore_action),
                    onClick = onRequestIgnoreBatteryOptimizations,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ButtonRow(
                cancelText = stringResource(R.string.action_cancel),
                onCancel = onCancel,
                confirmText = stringResource(R.string.perm_action_go_settings),
                onConfirm = onOpenSettings,
            )
        }
    }
}

@Composable
private fun ReasonRow(
    name: String,
    reason: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        MiuixText(
            text = "· $name",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        MiuixText(
            text = reason,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

@Composable
private fun ButtonRow(
    cancelText: String,
    onCancel: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            text = cancelText,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
    }
}
