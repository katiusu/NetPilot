package io.github.katiusu.netpilot.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SectionTitle(text: String) {
    SmallTitle(text = text)
}

private val SettingsItemMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

@Composable
fun SettingsAction(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    endIcon: ImageVector? = null,
    endIconSize: Dp = 20.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary?.takeIf { it.isNotBlank() },
        enabled = enabled,
        startAction = icon?.let { vector ->
            {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        },
        endActions = endIcon?.let { vector ->
            {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = if (enabled) {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                    modifier = Modifier
                        .padding(end = 18.dp)
                        .size(endIconSize),
                )
            }
        },
        insideMargin = SettingsItemMargin,
        onClick = onClick,
    )
}

@Composable
fun SettingsActionWithArrow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary?.takeIf { it.isNotBlank() },
        enabled = enabled,
        startAction = icon?.let { vector ->
            {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        },
        endActions = {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier
                    .padding(end = 18.dp)
                    .size(width = 10.dp, height = 16.dp),
            )
        },
        insideMargin = SettingsItemMargin,
        onClick = onClick,
    )
}
