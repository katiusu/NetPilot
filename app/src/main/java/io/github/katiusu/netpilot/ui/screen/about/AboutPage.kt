package io.github.katiusu.netpilot.ui.screen.about

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
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
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.update.UpdateChecker
import io.github.katiusu.netpilot.ui.component.AppPageScaffold
import io.github.katiusu.netpilot.ui.component.UpdateDialog
import io.github.katiusu.netpilot.ui.component.effect.BgEffectBackground
import io.github.katiusu.netpilot.ui.component.isGlassTopBarAvailable
import io.github.katiusu.netpilot.ui.theme.squircleShape
import io.github.katiusu.netpilot.ui.util.ColorBlendToken
import io.github.katiusu.netpilot.ui.util.isInDarkTheme
import io.github.katiusu.netpilot.ui.util.pageContentPadding
import io.github.katiusu.netpilot.ui.util.pageScrollModifiers
import io.github.katiusu.netpilot.ui.util.rememberBlurBackdrop
import kotlin.math.abs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

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

    val collapsed by remember { derivedStateOf { scrollProgress == 1f } }
    val fallbackBarColor = !isGlassTopBarAvailable()

    AppPageScaffold(
        title = stringResource(R.string.about),
        isBlurEnabled = isBlurEnabled,
        scrollBehavior = topAppBarScrollBehavior,
        titleAlpha = { ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f) },
        expandable = false,
        color = when {
            !fallbackBarColor -> Color.Transparent
            collapsed -> null
            else -> Color.Transparent
        },
    ) { innerPadding ->
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
    var viewportHeightDp by remember { mutableStateOf(0.dp) }
    val appName = stringResource(R.string.app_name)
    val ctx = LocalContext.current
    val appIcon = remember(ctx, density) {
        val iconSizePx = with(density) { 100.dp.roundToPx() }
        ctx.packageManager.getApplicationIcon(ctx.applicationInfo)
            .toBitmap(iconSizePx, iconSizePx)
            .asImageBitmap()
    }
    val versionName = remember(ctx) {
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName ?: "1.0"} (${info.longVersionCode})"
        } catch (_: Exception) { "1.0" }
    }

    val updateScope = rememberCoroutineScope()
    val appVersion = remember(ctx) { UpdateChecker.currentVersion(ctx) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    fun checkUpdate() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        updateScope.launch {
            val result = UpdateChecker.checkForUpdate(ctx)
            isCheckingUpdate = false
            result.fold(
                onSuccess = { info ->
                    if (info.hasUpdate) {
                        updateInfo = info
                    } else {
                        Toast.makeText(ctx, ctx.getString(R.string.update_latest), Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = {
                    Toast.makeText(ctx, ctx.getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            onDismissRequest = { updateInfo = null },
            onConfirm = {
                updateInfo = null
                openExternalUrl(ctx, info.releaseUrl)
            },
        )
    }

    BgEffectBackground(
        dynamicBackground = true,
        isFullSize = true,
        modifier = Modifier.fillMaxSize(),
        bgModifier = if (contentBackdrop != null) Modifier.layerBackdrop(contentBackdrop) else Modifier,
        alpha = { 1f - scrollProgressProvider() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(logoPadding)
                .padding(top = 52.dp)
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
                                shape = squircleShape(16.dp),
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

        LaunchedEffect(lazyListState) {
            snapshotFlow {
                Triple(
                    lazyListState.isScrollInProgress,
                    lazyListState.firstVisibleItemIndex,
                    lazyListState.firstVisibleItemScrollOffset,
                )
            }.collect { (scrolling, firstIndex, offset) ->
                if (scrolling || firstIndex != 0) return@collect
                val spacerSize = lazyListState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == "logoSpacer" }?.size?.toFloat() ?: return@collect
                if (spacerSize <= 0f) return@collect
                val target = if (offset >= spacerSize / 2f) spacerSize else 0f
                if (abs(offset - target) < 1f) return@collect
                lazyListState.animateScrollBy(target - offset)
            }
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> with(density) { viewportHeightDp = size.height.toDp() } }
                .pageScrollModifiers(
                    showTopAppBar = true,
                    topAppBarScrollBehavior = topAppBarScrollBehavior,
                ),
            contentPadding = remember(scrollPadding) {
                object : PaddingValues by scrollPadding {
                    override fun calculateBottomPadding() = 0.dp
                }
            },
        ) {
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp,
                        ),
                )
            }

            item(key = "about") {
                Column(
                    modifier = Modifier
                        .heightIn(
                            min = (viewportHeightDp - scrollPadding.calculateTopPadding())
                                .coerceAtLeast(0.dp),
                        )
                        .padding(bottom = scrollPadding.calculateBottomPadding()),
                ) {
                    val cardModifier = Modifier
                        .padding(horizontal = 12.dp)
                        .then(
                            if (contentBackdrop != null) {
                                Modifier.textureBlur(
                                    backdrop = contentBackdrop,
                                    shape = squircleShape(16.dp),
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
                            },
                        )
                    val cardColors = CardDefaults.defaultColors(
                        if (contentBackdrop != null) Color.Transparent else colorScheme.surfaceContainer,
                        Color.Transparent,
                    )

                    Card(
                        modifier = cardModifier,
                        colors = cardColors,
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.ab_github_repo),
                            summary = stringResource(R.string.ab_github_repo_summary),
                            onClick = { openExternalUrl(ctx, GITHUB_REPO_URL) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.np_home_link_changelog),
                            summary = stringResource(R.string.np_about_releases_summary),
                            onClick = { openExternalUrl(ctx, GITHUB_RELEASES_URL) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.np_home_link_issues),
                            summary = stringResource(R.string.np_about_issues_summary),
                            onClick = { openExternalUrl(ctx, GITHUB_ISSUES_URL) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.check_update),
                            summary = if (isCheckingUpdate) {
                                stringResource(R.string.check_update_checking)
                            } else {
                                stringResource(R.string.check_update_summary, appVersion)
                            },
                            onClick = { checkUpdate() },
                        )
                    }

                    Card(
                        modifier = cardModifier.padding(top = 12.dp),
                        colors = cardColors,
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

private const val GITHUB_REPO_URL = "https://github.com/katiusu/NetPilot"
private const val GITHUB_RELEASES_URL = "https://github.com/katiusu/NetPilot/releases"
private const val GITHUB_ISSUES_URL = "https://github.com/katiusu/NetPilot/issues"
private const val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0.txt"

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
