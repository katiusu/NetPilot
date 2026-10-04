package com.katiusu.netpilot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.PermissionGuide
import com.katiusu.netpilot.ui.component.FirstLaunchGuideDialog
import com.katiusu.netpilot.ui.component.PermissionDeniedDialog
import com.katiusu.netpilot.ui.component.PermissionRationaleDialog
import com.katiusu.netpilot.ui.component.liquid.IosLiquidGlassNavigationBar
import com.katiusu.netpilot.ui.screen.features.FeaturesPageView
import com.katiusu.netpilot.ui.screen.log.LogPageView
import com.katiusu.netpilot.ui.screen.monitor.MonitorPageView
import com.katiusu.netpilot.ui.screen.home.HomePageView
import com.katiusu.netpilot.ui.screen.settings.SettingsPageView
import com.katiusu.netpilot.ui.theme.AppTheme
import com.katiusu.netpilot.ui.util.applyWindowBackground
import com.katiusu.netpilot.ui.util.isInDarkTheme
import com.katiusu.netpilot.ui.util.shouldExpandNavigationRail
import com.katiusu.netpilot.ui.util.shouldShowSplitPane
import com.katiusu.netpilot.bridge.XposedServiceManager
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.RecordingTape
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.springAnimateToPage

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时重新检测 Root（用户可能刚刚授予权限）。
        XposedServiceManager.checkRoot()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        val savedSettings = AppSettings.load(this)

        // 同步桌面图标可见状态，避免偏好与系统组件状态不一致。
        LauncherIconController.apply(this, savedSettings.hideLauncherIcon)

        // 覆盖 XML 主题的窗口背景，兼容「系统浅色但应用内手动强制深色」的情况，避免启动白屏闪烁。
        applyWindowBackground(savedSettings.themeMode)

        setContent {
            var themeMode by remember {
                mutableStateOf(
                    try { ColorSchemeMode.valueOf(savedSettings.themeMode) }
                    catch (_: Exception) { ColorSchemeMode.System }
                )
            }
            var isFloatingNavbar by remember { mutableStateOf(savedSettings.isFloatingNavbar) }
            var isLiquidGlass by remember { mutableStateOf(savedSettings.isLiquidGlass) }
            var isBlurEnabled by remember { mutableStateOf(savedSettings.isBlurEnabled) }


            // 运行时权限：不申请的话 TelephonyManager.signalStrength 返回 null、
            // activeSubscriptionInfoList 抛 SecurityException —— 表现就是「SIM 显示无卡」
            // 「SINR 一直未知」，网络质量判定也永远不会触发（读不到 RSRP 就永远不满足强信号条件）。
            // 申请哪几项、为什么需要、该弹哪个框全部由 PermissionGuide 判定，这里只做接线。
            var permissionPrompt by remember {
                mutableStateOf<PermissionGuide.StartupPrompt?>(null)
            }
            var promptItems by remember { mutableStateOf<List<PermissionGuide.Item>>(emptyList()) }
            var showFirstLaunchGuide by remember { mutableStateOf(false) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                // 只处理用户在这轮系统对话框里拒绝的项；被拒就弹「被拒说明」并记下已提示，
                // 免得用户明确「稍后」之后每次冷启动又被弹一次。
                val denied = PermissionGuide.ITEMS.filter { result[it.permission] == false }
                if (denied.isEmpty()) {
                    permissionPrompt = null
                } else {
                    PermissionGuide.markDeniedPrompted()
                    promptItems = denied
                    permissionPrompt = PermissionGuide.StartupPrompt.DENIED
                }
            }
            LaunchedEffect(Unit) {
                when (PermissionGuide.startupPrompt(this@MainActivity)) {
                    PermissionGuide.StartupPrompt.RATIONALE -> {
                        // 先讲原因，用户点「继续授权」才调起系统权限框（见下面的对话框）。
                        PermissionGuide.markRationaleShown()
                        promptItems = PermissionGuide.missingItems(this@MainActivity)
                        permissionPrompt = PermissionGuide.StartupPrompt.RATIONALE
                    }
                    PermissionGuide.StartupPrompt.DENIED -> {
                        // 理由说明看过但权限仍缺：直接给「去设置」，不再调系统权限框（大概率又被拒）。
                        PermissionGuide.markDeniedPrompted()
                        promptItems = PermissionGuide.missingItems(this@MainActivity)
                        permissionPrompt = PermissionGuide.StartupPrompt.DENIED
                    }
                    PermissionGuide.StartupPrompt.NONE -> permissionPrompt = null
                }
                if (PermissionGuide.shouldShowFirstLaunchGuide()) {
                    // 只弹一次；真正的渲染排在上面的权限框之后（渲染处有 permissionPrompt == null 判断），
                    // 否则两个 WindowDialog 会在同一帧叠在一起。
                    PermissionGuide.markFirstLaunchGuideShown()
                    showFirstLaunchGuide = true
                }
            }

            // 网络质量降级默认开启，但监控循环挂在前台服务上：冷启动时若开关开着而服务
            // 没跑，界面会显示「已开启」却什么都不做 —— 正是本门面一直在避免的假状态。
            // 这里补一次对账。刻意放在 Activity 的 LaunchedEffect 而不是 Application.onCreate：
            // targetSdk 34 下从后台起前台服务会被 ForegroundServiceStartNotAllowedException 拒掉。
            LaunchedEffect(Unit) {
                val appContext = this@MainActivity.applicationContext
                if (NetPilot.autoDowngradeEnabled() &&
                    NetPilot.servicesEnabled(appContext) &&
                    !NetPilot.monitorRunning()
                ) {
                    NetPilot.setMonitorOnly(appContext, true)
                }
            }

            fun persistState() {
                AppSettings.save(
                    this@MainActivity,
                    AppSettings(
                        themeMode = themeMode.name,
                        isFloatingNavbar = isFloatingNavbar,
                        isLiquidGlass = isLiquidGlass,
                        isBlurEnabled = isBlurEnabled,
                        hideLauncherIcon = savedSettings.hideLauncherIcon,
                        language = LocaleHelper.getSavedLanguage(this@MainActivity).code,
                    )
                )
            }

            AppTheme(themeMode = themeMode) {
                MainScreen(
                    themeMode = themeMode,
                    isFloatingNavbar = isFloatingNavbar,
                    isLiquidGlass = isLiquidGlass,
                    isBlurEnabled = isBlurEnabled,
                    onThemeModeChange = { themeMode = it; persistState() },
                    onFloatingNavbarChange = { isFloatingNavbar = it; persistState() },
                    onLiquidGlassChange = { isLiquidGlass = it; persistState() },
                    onBlurEnabledChange = { isBlurEnabled = it; persistState() },
                )

            }

            // 权限引导：只在需要时出现。权限齐全（startupPrompt == NONE）时不弹任何对话框。
            when (permissionPrompt) {
                PermissionGuide.StartupPrompt.RATIONALE -> PermissionRationaleDialog(
                    items = promptItems,
                    onCancel = {
                        // 在理由说明里点了取消：连系统权限框都不调起，记为「已知晓并跳过」。
                        PermissionGuide.skipPermissionGuide()
                        permissionPrompt = null
                    },
                    onContinue = {
                        permissionPrompt = null
                        // 只申请尚未授予的那些；若用户刚在设置里给全了，这里就什么都不做。
                        val missing = PermissionGuide.missingPermissionNames(this@MainActivity)
                        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                    },
                )
                PermissionGuide.StartupPrompt.DENIED -> PermissionDeniedDialog(
                    items = promptItems,
                    onOpenSettings = {
                        PermissionGuide.openAppDetails(this@MainActivity)
                        permissionPrompt = null
                    },
                    onLater = { permissionPrompt = null },
                )
                PermissionGuide.StartupPrompt.NONE, null -> Unit
            }

            if (showFirstLaunchGuide && permissionPrompt == null) {
                // 已经「忽略电池优化」就不再显示这条快捷入口，避免用户重复操作。
                val onIgnoreBatteryOpt: (() -> Unit)? =
                    if (PermissionGuide.isIgnoringBatteryOptimizations(this@MainActivity)) {
                        null
                    } else {
                        { PermissionGuide.requestIgnoreBatteryOptimizations(this@MainActivity) }
                    }
                FirstLaunchGuideDialog(
                    onCancel = { showFirstLaunchGuide = false },
                    onOpenSettings = {
                        // 先 MIUI/HyperOS 自启动管理页，不可用再回落应用程式详情；内部全程 runCatching。
                        PermissionGuide.openStartupSettings(this@MainActivity)
                        showFirstLaunchGuide = false
                    },
                    onRequestIgnoreBatteryOptimizations = onIgnoreBatteryOpt,
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    themeMode: ColorSchemeMode,
    isFloatingNavbar: Boolean,
    isLiquidGlass: Boolean,
    isBlurEnabled: Boolean,
    onThemeModeChange: (ColorSchemeMode) -> Unit,
    onFloatingNavbarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 5 })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var homeRefreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val items = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_features),
        stringResource(R.string.tab_monitor),
        stringResource(R.string.tab_log),
        stringResource(R.string.tab_settings)
    )
    val icons = listOf(
        MiuixIcons.Home,
        MiuixIcons.ListView,
        MiuixIcons.Stopwatch,
        MiuixIcons.RecordingTape,
        MiuixIcons.Settings
    )

    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedIndex != pagerState.currentPage) {
            selectedIndex = pagerState.currentPage
            homeRefreshKey++
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val blurActive = isBlurEnabled

    val navBarMode = if (!isFloatingNavbar) 0 else if (!isLiquidGlass) 1 else 2
    val isWideScreen = shouldShowSplitPane()
    // 横屏时仅普通底栏改为左侧 NavigationRail；悬浮栏与液态玻璃保留底部。
    val useNavigationRail = isWideScreen && navBarMode == 0

    val railState = rememberNavigationRailState()
    val expandRail = shouldExpandNavigationRail()
    LaunchedEffect(expandRail) {
        if (expandRail) railState.expand() else railState.collapse()
    }

    val onItemSelected: (Int) -> Unit = select@{ index ->
        if (index == selectedIndex) return@select
        homeRefreshKey++
        navJob?.cancel()
        selectedIndex = index
        isNavigating = true
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(index)
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != index) {
                        selectedIndex = pagerState.currentPage
                    }
                }
            }
        }
    }

    val pagerContent: @Composable (Dp, PaddingValues) -> Unit = { navBarHeight, pagerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(Modifier.layerBackdrop(backdrop))
                .background(surfaceColor)
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                contentPadding = pagerPadding,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
            ) { page ->
                when (page) {
                    0 -> HomePageView(
                        isBlurEnabled = isBlurEnabled,
                        refreshKey = homeRefreshKey,
                        extraBottomPadding = navBarHeight,
                        onNavigate = { onItemSelected(it) },
                    )
                    1 -> FeaturesPageView(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                    )
                    4 -> SettingsPageView(
                        currentMode = themeMode,
                        onModeChange = onThemeModeChange,
                        isFloatingNavbar = isFloatingNavbar,
                        onFloatingNavbarChange = onFloatingNavbarChange,
                        isLiquidGlass = isLiquidGlass,
                        onLiquidGlassChange = onLiquidGlassChange,
                        isBlurEnabled = isBlurEnabled,
                        onBlurEnabledChange = onBlurEnabledChange,
                        extraBottomPadding = navBarHeight,
                    )
                    2 -> MonitorPageView(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                    )
                    3 -> LogPageView(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                    )
                }
            }
        }
    }

    if (useNavigationRail) {
        val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val density = LocalDensity.current
        var railWidthPx by remember { mutableIntStateOf(0) }
        val railWidth = with(density) { railWidthPx.toDp() }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(
                        WindowInsets.systemBars
                            .add(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Start)
                    )
            ) {
                pagerContent(navigationBarBottom, PaddingValues(start = railWidth))
            }
            NavigationRail(
                modifier = Modifier
                    .onSizeChanged { railWidthPx = it.width }
                    .then(
                        if (blurActive) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RectangleShape,
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.5f)),
                                    ),
                                ),
                            )
                        } else {
                            Modifier
                        }
                    ),
                color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface,
                state = railState,
            ) {
                items.forEachIndexed { index, label ->
                    NavigationRailItem(
                        selected = selectedIndex == index,
                        onClick = { onItemSelected(index) },
                        icon = icons[index],
                        label = label,
                    )
                }
            }
        }
    } else {
        Scaffold(
            popupHost = { },
            bottomBar = {
                BottomNavigationBar(
                    mode = navBarMode,
                    items = items,
                    icons = icons,
                    selectedIndex = selectedIndex,
                    backdrop = backdrop,
                    blurActive = blurActive,
                    onItemSelected = onItemSelected,
                )
            }
        ) { globalPadding ->
            pagerContent(globalPadding.calculateBottomPadding(), PaddingValues(0.dp))
        }
    }
}

@Composable
private fun BottomNavigationBar(
    mode: Int,
    items: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selectedIndex: Int,
    backdrop: LayerBackdrop?,
    blurActive: Boolean,
    onItemSelected: (Int) -> Unit,
) {
    when (mode) {
        2 -> {
            val navigationItems = remember(items, icons) {
                List(items.size) { i -> NavigationItem(items[i], icons[i]) }
            }
            // 始终限制液态玻璃底栏宽度并居中，保持与手机竖屏一致的小尺寸。
            val liquidModifier = Modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 440.dp)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                IosLiquidGlassNavigationBar(
                    modifier = liquidModifier,
                    items = navigationItems,
                    selectedIndex = selectedIndex,
                    onItemClick = { index ->
                        onItemSelected(index)
                    },
                    backdrop = backdrop,
                    isBlurActive = blurActive,
                )
            }
        }
        1 -> {
            val floatingBarColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
            val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
            val isDark = isInDarkTheme()
            val floatingHighlight = remember(isDark) {
                if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
            }
            FloatingNavigationBar(
                modifier = if (blurActive) {
                    Modifier.textureBlur(
                        backdrop = backdrop!!,
                        shape = floatingBarShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.4f)),
                            ),
                        ),
                        highlight = floatingHighlight,
                    )
                } else {
                    Modifier
                },
                color = floatingBarColor,
            ) {
                items.forEachIndexed { index, label ->
                    FloatingNavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { onItemSelected(index) },
                        icon = icons[index],
                        label = label,
                        enabled = true
                    )
                }
            }
        }
        else -> {
            val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
            Box(
                modifier = Modifier
                    .then(
                        if (blurActive) {
                            Modifier.textureBlur(
                                backdrop = backdrop!!,
                                shape = RectangleShape,
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.5f)),
                                    ),
                                ),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(barColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
            ) {
                NavigationBar(
                    color = barColor,
                ) {
                    items.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { onItemSelected(index) },
                            icon = icons[index],
                            label = label,
                            enabled = true
                        )
                    }
                }
            }
        }
    }
}
