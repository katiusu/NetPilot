package io.github.katiusu.netpilot.ui.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.update.UpdateChecker
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppDialog(
        show = true,
        title = stringResource(R.string.update_available_title),
        summary = stringResource(
            R.string.update_available_summary,
            info.currentVersion,
            info.latestVersion,
        ),
        onDismissRequest = onDismissRequest,
    ) {
        if (info.releaseNotes.isNotBlank()) {
            UpdateMarkdown(info.releaseNotes)
        }
        AppDialogButtonRow {
            AppDialogButton(
                text = stringResource(R.string.update_later),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            AppDialogButton(
                text = stringResource(R.string.update_now),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }
    }
}

@Composable
internal fun CurrentChangelogDialog(version: String, notes: String, onDismissRequest: () -> Unit) {
    AppDialog(
        show = true,
        title = stringResource(R.string.current_changelog),
        summary = stringResource(R.string.check_update_summary, version),
        onDismissRequest = onDismissRequest,
    ) {
        if (notes.isBlank()) {
            BasicText(
                text = stringResource(R.string.current_changelog_unavailable),
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
            )
        } else {
            UpdateMarkdown(notes)
        }
        AppDialogButtonRow {
            AppDialogButton(
                text = stringResource(R.string.action_confirm),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }
    }
}

@Composable
private fun UpdateMarkdown(markdown: String) {
    BasicText(
        text = markdown,
        style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 8.dp),
    )
}
