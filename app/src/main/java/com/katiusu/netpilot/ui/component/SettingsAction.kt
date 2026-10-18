package com.katiusu.netpilot.ui.component

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

/**
 * 页面分节标题。
 *
 * 主页/关于页重写（1.6.0）后各页的行尺寸必须一致，所以统一走 [SmallTitle] 的默认内边距，
 * 不在这里再叠一层 padding —— 叠了以后同一张卡片里的行会和别的页对不齐。
 */
@Composable
fun SectionTitle(text: String) {
    SmallTitle(text = text)
}

/** 行内容边距与参照工程（HyperIsland, MIT）一致：左右 18dp、上下 14dp。 */
private val SettingsItemMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

/**
 * 一行「可点动作」：标题 + 说明 + 右侧图标。
 *
 * 结构照 HyperIsland 3.2.1（MIT, © 2026 1812z）的 `SettingsAction` 改写：
 * 根节点直接用 Miuix 的 [BasicComponent]，按下高亮、行高、可用性置灰全部交给组件自己，
 * 避免每个页面手写 Row 导致行高/内边距各页不一致。
 *
 * @param icon 左侧图标（可选）。与 [endIcon] 同时给会显得有些拥挤，一般只用其中一个。
 * @param endIcon 右侧图标（外链常用 `MiuixIcons.Link`）；需要「进入下一页」的箭头请用 [SettingsActionWithArrow]。
 */
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

/**
 * 与 [SettingsAction] 相同，但右侧是「进入下一页」的箭头而不是外链图标。
 */
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
                // 箭头按参照工程固定成 10×16dp：用 size(Dp) 会让箭头在 18dp 行里显得过大。
                modifier = Modifier
                    .padding(end = 18.dp)
                    .size(width = 10.dp, height = 16.dp),
            )
        },
        insideMargin = SettingsItemMargin,
        onClick = onClick,
    )
}
