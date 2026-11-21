package io.github.katiusu.netpilot.ui.component

import android.os.Build
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.ui.util.pageStartInset
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppTopAppBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    color: Color = MiuixTheme.colorScheme.surface,
    backdrop: LayerBackdrop? = null,
    onBack: (() -> Unit)? = null,
    titleAlpha: () -> Float = { 1f },
    expandable: Boolean = true,
    useGlassTopBar: Boolean = true,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val glass = useGlassTopBar && isGlassTopBarAvailable()
    val chromeAlpha = LocalAppTopBarTitleAlpha.current
    val compact = LocalAppTopBarCompact.current
    val mergedTitleAlpha: () -> Float = { (titleAlpha() * chromeAlpha()).coerceIn(0f, 1f) }
    val centerObstructionStart = LocalAppTopBarCenterObstructionStart.current()
    val backButton: @Composable () -> Unit = {
        CompositionLocalProvider(LocalIsGlassTopBar provides glass) {
            if (onBack != null) {
                BackNavigationButton(
                    onClick = onBack,
                    glass = isGlassTopBarAvailable(),
                    backdrop = backdrop,
                )
            }
            navigationIcon()
        }
    }
    val scopedActions: @Composable RowScope.() -> Unit = {
        CompositionLocalProvider(LocalIsGlassTopBar provides glass) {
            actions()
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val bandBrush = remember(surfaceColor, color, glass) {
        if (glass) {
            GlassTopAppBarDefaults.bandBrush(surfaceColor)
        } else {
            SolidColor(color)
        }
    }
    val contentScrolled by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.contentOffset < 0f }
    }
    GlassTopAppBar(
        title = title,
        isContentScrolled = contentScrolled,
        contentModifier = Modifier.windowInsetsPadding(appTopBarSafeInsets()).pageStartInset(),
        backdrop = if (glass) backdrop else null,
        progressiveBlur = glass && backdrop != null,
        scrollBehavior = scrollBehavior,
        expandable = expandable && !compact,
        bandBrush = bandBrush,
        bandOverhang = GlassTopAppBarDefaults.BandOverhang,
        backgroundAlpha = { 1f },
        defaultWindowInsetsPadding = false,
        titleAlpha = mergedTitleAlpha,
        centerObstructionStart = centerObstructionStart,
        navigationIcon = backButton,
        actions = scopedActions,
    )
}

@Composable
internal fun appTopBarSafeInsets(): WindowInsets =
    WindowInsets.systemBars
        .union(WindowInsets.captionBar)
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top)

@Composable
fun AppSubPageTopAppBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    color: Color = MiuixTheme.colorScheme.surface,
    backdrop: LayerBackdrop? = null,
    expandable: Boolean = true,
    useGlassTopBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AppTopAppBar(
        title = title,
        scrollBehavior = scrollBehavior,
        color = color,
        backdrop = backdrop,
        onBack = onBack,
        expandable = expandable,
        useGlassTopBar = useGlassTopBar,
        actions = actions,
    )
}

val LocalIsGlassTopBar: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

val LocalAppTopBarTitleAlpha: ProvidableCompositionLocal<() -> Float> = staticCompositionLocalOf { { 1f } }

val LocalAppTopBarCompact: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

internal val LocalAppTopBarCenterObstructionStart = staticCompositionLocalOf { { Dp.Unspecified } }

@Composable
fun AppTopAppBarIconAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    if (LocalIsGlassTopBar.current) {
        GlassIconButton(onClick = onClick, content = content)
    } else {
        IconButton(onClick = onClick, content = content)
    }
}

@Composable
private fun BackNavigationButton(
    onClick: () -> Unit,
    glass: Boolean,
    backdrop: LayerBackdrop? = null,
) {
    val tint = if (glass) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onBackground
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = if (glass) MiuixIcons.Glass.ChevronBackward else MiuixIcons.Glass.Back,
            contentDescription = stringResource(R.string.action_back),
            tint = tint,
            modifier = Modifier.size(if (glass) 28.dp else 24.dp),
        )
    }
    if (glass) {
        GlassIconButton(
            onClick = onClick,
            backdrop = backdrop,
            content = content,
        )
    } else {
        IconButton(onClick = onClick, content = content)
    }
}

internal fun isGlassTopBarAvailable(): Boolean =
    isRuntimeShaderSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
