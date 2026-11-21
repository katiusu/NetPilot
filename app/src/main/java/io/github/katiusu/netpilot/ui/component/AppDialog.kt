package io.github.katiusu.netpilot.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.katiusu.netpilot.ui.util.LocalPreferenceBackdrop
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppDialog(
    show: Boolean,
    title: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enableWindowDim: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalPreferenceBackdrop.current
    GlassDialog(
        onDismissRequest = onDismissRequest,
        show = show,
        backdrop = backdrop,
        modifier = modifier,
        enableWindowDim = enableWindowDim,
        renderInRootScaffold = true,
        title = title,
        summary = summary,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun AppDialogButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun AppDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val textStyle = MiuixTheme.textStyles.button
    val styleFontSize = textStyle.fontSize
    val maxFontSize = if (styleFontSize.type == TextUnitType.Sp) styleFontSize else 17.sp
    GlassDialogButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        role = when {
            primary -> GlassDialogButtonRole.Primary
            destructive -> GlassDialogButtonRole.Destructive
            else -> GlassDialogButtonRole.Normal
        },
    ) {
        MiuixText(
            text = text,
            style = textStyle,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 11.sp,
                maxFontSize = maxFontSize,
            ),
        )
    }
}
