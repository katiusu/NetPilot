package com.katiusu.netpilot.ui.screen.about

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.katiusu.netpilot.R
import com.katiusu.netpilot.ui.component.effect.BgEffectBackground
import com.katiusu.netpilot.ui.util.BlurredBar
import com.katiusu.netpilot.ui.util.ColorBlendToken
import com.katiusu.netpilot.ui.util.blurSource
import com.katiusu.netpilot.ui.util.isInDarkTheme
import com.katiusu.netpilot.ui.util.pageContentPadding
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import com.katiusu.netpilot.ui.util.rememberBlurBackdrop
import com.katiusu.netpilot.ui.util.rememberBlurState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Composable
fun AboutPageContent(
    openLicensePage: () -> Unit,
    isBlurEnabled: Boolean = true,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()

    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val hazeState = rememberBlurState()
    val collapsed by remember { derivedStateOf { scrollProgress == 1f } }
    val blurActive by remember(hazeState, isBlurEnabled) { derivedStateOf { isBlurEnabled && hazeState != null && scrollProgress == 1f } }

    Scaffold(
        topBar = {
            val barColor = if (blurActive) {
                Color.Transparent
            } else {
                if (collapsed) colorScheme.surface else Color.Transparent
            }
            val titleColor = colorScheme.onSurface.copy(
                alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
            )
            BlurredBar(hazeState, blurActive) {
                SmallTopAppBar(
                    title = stringResource(R.string.about),
                    scrollBehavior = topAppBarScrollBehavior,
                    color = barColor,
                    titleColor = titleColor,
                    defaultWindowInsetsPadding = false,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = Modifier.blurSource(if (isBlurEnabled) hazeState else null)) {
            AboutContent(
                padding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = 0.dp,
                ),
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                lazyListState = lazyListState,
                scrollProgressProvider = { scrollProgress },
                openLicensePage = openLicensePage,
            )
        }
    }
}

@Composable
private fun AboutContent(
    padding: PaddingValues,
    topAppBarScrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgressProvider: () -> Float,
    openLicensePage: () -> Unit,
) {
    val contentBackdrop = rememberBlurBackdrop()
    var blurRadius by remember { mutableFloatStateOf(60f) }
    var noiseCoefficient by remember { mutableFloatStateOf(BlurDefaults.NoiseCoefficient) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }

    val scrollPadding = pageContentPadding(
        padding,
        padding,
        false,
        extraStart = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues().calculateRightPadding(LayoutDirection.Ltr),
    )
    val logoPadding = pageContentPadding(
        padding,
        padding,
        false,
        extraTop = 40.dp,
        extraStart = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues().calculateRightPadding(LayoutDirection.Ltr),
    )

    val isInDark = isInDarkTheme()

    val cardBlend = if (isInDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
    val logoBlend = remember(isInDark) {
        if (isInDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(300.dp) }
    val appName = stringResource(R.string.app_name)
    val ctx = LocalContext.current
    // 直接读取应用启动图标，保证与桌面图标始终一致。
    val appIcon = remember(ctx, density) {
        val iconSizePx = with(density) { 100.dp.roundToPx() }
        ctx.packageManager.getApplicationIcon(ctx.applicationInfo)
            .toBitmap(iconSizePx, iconSizePx)
            .asImageBitmap()
    }
    // 同时展示 versionName 与 versionCode：前者是语义版本，后者是构建号
    // （形如 2026100500）。用户报问题时给构建号才有意义，光给语义版本定位不到具体构建。
    // remember：版本信息一次取到就够，避免每次重组都走一遍 PackageManager 并新建字符串。
    val versionName = remember(ctx) {
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            // minSdk 34 已经高于 P，longVersionCode 必然可用；旧的 versionCode 字段已废弃。
            "${info.versionName ?: "1.0"} (${info.longVersionCode})"
        } catch (_: Exception) { "1.0" }
    }

    BgEffectBackground(
        dynamicBackground = true,
        isFullSize = true,
        modifier = Modifier.fillMaxSize(),
        bgModifier = if (contentBackdrop != null) Modifier.layerBackdrop(contentBackdrop) else Modifier,
        alpha = { 1f - scrollProgressProvider() },
    ) {
        // Logo area — floating overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateLeftPadding(LayoutDirection.Ltr),
                    end = logoPadding.calculateRightPadding(LayoutDirection.Ltr),
                )
                .onSizeChanged { size ->
                    with(density) { logoHeightDp = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        val iconProgress = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
                        clip = true
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    }
                    .squircleClip(cornerRadius = 28.dp),
            ) {
                Image(
                    modifier = Modifier.size(100.dp),
                    bitmap = appIcon,
                    contentDescription = null,
                )
            }
            MiuixText(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .graphicsLayer {
                        val projectNameProgress = ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (contentBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = contentBackdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 150f,
                                noiseCoefficient = noiseCoefficient,
                                colors = BlurDefaults.blurColors(
                                    blendColors = logoBlend,
                                ),
                                contentBlendMode = ComposeBlendMode.DstIn,
                            )
                        } else {
                            Modifier
                        },
                    ),
                text = appName,
                color = colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            MiuixText(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val versionCodeProgress = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    },
                color = colorScheme.onSurfaceVariantSummary,
                text = versionName,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        // Scrollable content
        // 卡片底色（可选的纹理模糊）。顺序很关键：卡片之间的间距必须写在 textureBlur **之前**。
        // 写在之后的话，这段间距会被一起画进模糊卡片的背景里 —— 两张卡片就直接连成一整块，
        // 间距变成卡片内部顶部的一条空白，看上去像「上一行的内容没删干净」。
        // 这里与 MiuixGui 例子 AboutPage 的写法保持一致。
        val aboutCardBackground = if (contentBackdrop != null) {
            Modifier.textureBlur(
                backdrop = contentBackdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = blurRadius,
                noiseCoefficient = noiseCoefficient,
                colors = BlurDefaults.blurColors(
                    blendColors = cardBlend,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                ),
            )
        } else {
            Modifier
        }
        val aboutCardColors = CardDefaults.defaultColors(
            if (contentBackdrop != null) Color.Transparent else colorScheme.surfaceContainer,
            Color.Transparent,
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .pageScrollModifiers(
                    showTopAppBar = true,
                    topAppBarScrollBehavior = topAppBarScrollBehavior,
                ),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                start = scrollPadding.calculateLeftPadding(LayoutDirection.Ltr),
                end = scrollPadding.calculateRightPadding(LayoutDirection.Ltr),
            ),
        ) {
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            // 首个卡片与标题区之间只留一点点呼吸空间。这里以前多加了 126.dp，
                            // 会让 GitHub 卡片上方凭空空出约 138dp 的大块空白，看着像内容没排干净。
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 8.dp,
                        ),
                )
            }

            item(key = "about") {
                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(bottom = scrollPadding.calculateBottomPadding()),
                ) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                            .then(aboutCardBackground),
                        colors = aboutCardColors,
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.ab_github_repo),
                            summary = stringResource(R.string.ab_github_repo_summary),
                            onClick = { openExternalUrl(ctx, GITHUB_REPO_URL) },
                        )
                    }
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 24.dp)
                            .then(aboutCardBackground),
                        colors = aboutCardColors,
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.about_license),
                            summary = stringResource(R.string.ab_license_summary),
                            onClick = { openExternalUrl(ctx, LICENSE_URL) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_dependencies),
                            summary = stringResource(R.string.ab_dependencies_summary),
                            onClick = openLicensePage,
                        )
                    }
                    MiuixText(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 20.dp),
                        color = colorScheme.onSurfaceVariantSummary,
                        text = stringResource(R.string.copyright),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

/** 仓库地址；与 [R.string.ab_github_repo_summary] 展示的字符串保持一致。 */
private const val GITHUB_REPO_URL = "https://github.com/katiusu/NetPilot"

/** Apache-2.0 协议原文。 */
private const val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0.txt"

/**
 * 用系统默认应用打开外部链接。
 *
 * 为什么不用 `LocalUriHandler`：找不到能处理该 URL 的应用时它会把 `ActivityNotFoundException`
 * 抛进组合里，整个「关于」页随之崩掉。这里整体 runCatching，失败只给一句 Toast。
 *
 * 另外拿到的 `context` 不保证是 Activity（Compose 里常常是 ContextWrapper），非 Activity
 * 上下文起 Activity 必须补 [Intent.FLAG_ACTIVITY_NEW_TASK]，否则同样会被系统拒绝。
 */
private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            Toast.makeText(context, context.getString(R.string.ab_open_failed), Toast.LENGTH_SHORT).show()
        }
    }
}
