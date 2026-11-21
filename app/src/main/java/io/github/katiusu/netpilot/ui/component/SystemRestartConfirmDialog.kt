package io.github.katiusu.netpilot.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.katiusu.netpilot.R

@Composable
fun SystemRestartConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        show = true,
        title = stringResource(R.string.restart_system_confirm_title),
        summary = stringResource(R.string.restart_system_confirm_message),
        onDismissRequest = onDismiss,
    ) {
        AppDialogButtonRow {
            AppDialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            AppDialogButton(
                text = stringResource(R.string.restart_system_confirm_action),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                destructive = true,
            )
        }
    }
}
