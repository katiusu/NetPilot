package io.github.katiusu.netpilot.ui.util

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

val LocalSubPageScrollBehavior: ProvidableCompositionLocal<ScrollBehavior?> =
    staticCompositionLocalOf { null }

class SubPageScrollGate {
    internal var contentScrollable: Boolean = true
}

val LocalSubPageScrollGate: ProvidableCompositionLocal<SubPageScrollGate?> =
    staticCompositionLocalOf { null }

val LocalPreferenceBackdrop: ProvidableCompositionLocal<LayerBackdrop?> = staticCompositionLocalOf { null }

val LocalAppPageStartInset: ProvidableCompositionLocal<() -> Dp> = staticCompositionLocalOf { { 0.dp } }

@Composable
fun Modifier.pageStartInset(): Modifier = padding(PaddingValues(0.dp).withPageStartInset())

@Composable
internal fun PaddingValues.withPageStartInset(): PaddingValues {
    val base = this
    val inset = LocalAppPageStartInset.current
    return remember(base, inset) {
        object : PaddingValues by base {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
                base.calculateLeftPadding(layoutDirection) + if (layoutDirection == LayoutDirection.Ltr) inset() else 0.dp

            override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
                base.calculateRightPadding(layoutDirection) + if (layoutDirection == LayoutDirection.Rtl) inset() else 0.dp
        }
    }
}

private object TopBarBlurConfig {
    const val BlurRadius: Float = 25f

    const val SurfaceAlpha: Float = 0.8f

    const val FullStrengthFraction: Float = 0.55f

    val FadeOverhang: Dp = 24.dp

    val ScrollFadeDistance: Dp = 0.dp

    val gradient: ProgressiveBlur = ProgressiveBlur(
        angle = 90f,
        startFraction = 0f,
        endFraction = 1f,
        curve = 1f,
    )

    @Composable
    fun colors(surface: Color): BlurColors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = surface.copy(alpha = SurfaceAlpha))),
    )
}

@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun rememberTopBarBackdrop(blurActive: Boolean): LayerBackdrop? =
    if (blurActive) rememberBlurBackdrop() else null

@Composable
fun PageBackdrop(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalPreferenceBackdrop provides backdrop) {
        Box(
            modifier = modifier.then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            content = content,
        )
    }
}


@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    val surface = MiuixTheme.colorScheme.surface
    val luminance = 0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue
    return luminance < 0.5f
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val blurActive = blurEnabled && backdrop != null
    val surfaceColor = MiuixTheme.colorScheme.surface
    val blurColors = TopBarBlurConfig.colors(surfaceColor)
    Box {
        if (blurActive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {
                            progressiveBlur(
                                radiusX = TopBarBlurConfig.BlurRadius,
                                gradient = TopBarBlurConfig.gradient,
                            )
                            blendColors(blurColors)
                        },
                    ),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val overhangPx = TopBarBlurConfig.FadeOverhang.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to surfaceColor.copy(alpha = 0.92f),
                                TopBarBlurConfig.FullStrengthFraction to surfaceColor.copy(alpha = 0.6f),
                                1f to Color.Transparent,
                                startY = 0f,
                                endY = size.height + overhangPx,
                            ),
                            size = Size(size.width, size.height + overhangPx),
                        )
                    },
            )
        }
        content()
    }
}

fun Modifier.uniformBlurBackdrop(
    backdrop: LayerBackdrop?,
    shape: androidx.compose.ui.graphics.Shape = RectangleShape,
    radius: Float = TopBarBlurConfig.BlurRadius,
): Modifier = if (backdrop == null) {
    this
} else {
    this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(radiusX = radius, radiusY = radius)
        },
    )
}

@Composable
fun Modifier.pageScrollModifiers(
    showTopAppBar: Boolean,
    topAppBarScrollBehavior: ScrollBehavior,
    contentState: LazyListState? = null,
): Modifier {
    val scrollGate = LocalSubPageScrollGate.current
    val state = topAppBarScrollBehavior.state
    if (scrollGate != null && contentState != null) {
        LaunchedEffect(scrollGate, contentState, state) {
            fun canClassify() = !contentState.isScrollInProgress &&
                state.heightOffset == 0f && state.overscrollOffset == 0f
            fun canScroll() = contentState.canScrollBackward || contentState.canScrollForward
            snapshotFlow {
                if (canClassify()) canScroll() else null
            }.collectLatest { scrollable ->
                if (scrollable == null) return@collectLatest
                withFrameNanos { }
                if (canClassify()) scrollGate.contentScrollable = canScroll()
            }
        }
    }

    val onOffsetChanged: (Float) -> Unit = remember(state, scrollGate) {
        { offset ->
            state.overscrollOffset = offset
            if (scrollGate != null && !scrollGate.contentScrollable) {
                state.heightOffset = offset.coerceAtMost(0f)
            }
        }
    }

    return this
        .scrollEndHaptic()
        .overScrollVertical(onOffsetChanged = onOffsetChanged)
        .then(if (showTopAppBar) Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection) else Modifier)
}

@Composable
fun pageContentPadding(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    extraTop: Dp = 0.dp,
    extraStart: Dp = 0.dp,
    extraEnd: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
): PaddingValues {
    val topPadding = innerPadding.calculateTopPadding() + extraTop
    val bottomPadding = if (isWideScreen) {
        outerPadding.calculateBottomPadding() + extraBottom +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
    } else {
        outerPadding.calculateBottomPadding() + extraBottom
    }
    return remember(topPadding, bottomPadding, extraStart, extraEnd) {
        PaddingValues(
            top = topPadding,
            start = extraStart,
            end = extraEnd,
            bottom = bottomPadding,
        )
    }.withPageStartInset()
}

@Composable
fun shouldShowSplitPane(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    return with(density) {
        val widthDp = windowInfo.containerSize.width.toDp()
        val heightDp = windowInfo.containerSize.height.toDp()
        val ratio = heightDp / widthDp
        widthDp >= 840.dp || (widthDp >= 600.dp && ratio < 1.2f)
    }
}

@Composable
fun shouldExpandNavigationRail(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    return with(density) {
        windowInfo.containerSize.width.toDp() >= 1200.dp
    }
}

object ColorBlendToken {
    val Pured_Regular_Light = listOf(
        BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
        BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
    )
    val Overlay_Thin_Light = listOf(
        BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
        BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
    )
}
