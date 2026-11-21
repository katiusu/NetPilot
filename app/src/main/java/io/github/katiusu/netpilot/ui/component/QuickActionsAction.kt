package io.github.katiusu.netpilot.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.ui.component.pref.QuickActionDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Suppress("unused")
@Composable
fun QuickActionsAction(
    packages: List<String>,
    modifier: Modifier = Modifier,
) {
    if (packages.isEmpty()) return
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }, modifier = modifier) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = stringResource(R.string.quick_action_title),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }

    if (showDialog) {
        QuickActionDialog(
            packages = packages,
            onDismiss = { showDialog = false },
        )
    }
}
