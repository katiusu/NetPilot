package io.github.katiusu.netpilot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import io.github.katiusu.netpilot.ui.component.search.AppSearchOverlay
import io.github.katiusu.netpilot.ui.component.search.AppSearchResults
import io.github.katiusu.netpilot.ui.component.search.AppSearchState
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationBarDefaults
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassNavigationRail
import top.yukonga.miuix.kmp.glass.GlassNavigationRailDefaults
import top.yukonga.miuix.kmp.glass.rememberGlassNavigationRailState
import top.yukonga.miuix.kmp.icon.glass.Search
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.PermissionGuide
import io.github.katiusu.netpilot.core.monitor.MonitorSettings
import io.github.katiusu.netpilot.core.update.UpdateChecker
import io.github.katiusu.netpilot.ui.component.FirstLaunchGuideDialog
import io.github.katiusu.netpilot.ui.component.UpdateDialog
import io.github.katiusu.netpilot.ui.component.PermissionDeniedDialog
import io.github.katiusu.netpilot.ui.component.PermissionRationaleDialog
import io.github.katiusu.netpilot.ui.component.liquid.IosLiquidGlassNavigationBar
import io.github.katiusu.netpilot.ui.screen.features.FeaturesPageView
import io.github.katiusu.netpilot.ui.screen.log.LogPageView
import io.github.katiusu.netpilot.ui.screen.monitor.MonitorPageView
import io.github.katiusu.netpilot.ui.screen.home.HomePageView
import io.github.katiusu.netpilot.ui.screen.settings.SettingsPageView
import io.github.katiusu.netpilot.ui.theme.AppTheme
import io.github.katiusu.netpilot.ui.util.applyWindowBackground
import io.github.katiusu.netpilot.ui.util.LocalTopLayer
import io.github.katiusu.netpilot.ui.util.TopLayerHost
import io.github.katiusu.netpilot.ui.util.TopLayerState
import io.github.katiusu.netpilot.ui.util.shouldShowSplitPane
import io.github.katiusu.netpilot.bridge.XposedServiceManager
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
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

    companion object {
        /** 从其它页面（快速操作）跳进来时指定的初始页。 */
        const val EXTRA_PAGE = "io.github.katiusu.netpilot.EXTRA_PAGE"

        const val PAGE_HOME = 0
        const val PAGE_FEATURES = 1
        const val PAGE_MONITOR = 2
        const val PAGE_LOG = 3
        const val PAGE_SETTINGS = 4

        /** onSaveInstanceState 里保存预测性返回开关用的键。 */
        private const val STATE_PREDICTIVE_BACK = "np_predictive_back"
    }

    /**
     * 外部请求的页（-1 = 不指定）。
     *
     * 为什么用状态而不是直接读 intent：本 Activity 是 singleTop，从快速操作页再跳回来时
     * 走的是 onNewIntent，只有把新请求写进状态，已经组合好的 MainScreen 才会跟着跳页。
     */
    private var requestedPage by mutableIntStateOf(-1)

    /**
     * 跳页请求的票号，每收到一次带 [EXTRA_PAGE] 的 intent 就自增。
     *
     * 为什么不能只靠 [requestedPage] 当重组键：目标页与当前值相同时 State 结构相等，
     * 写入不会触发重组，LaunchedEffect(requestedPage) 也就不会重跑——磁贴/快捷操作
     * 连点同一页时从第二次开始就静默失败。票号每来一次请求都变，等于把「一次请求」
     * 与「一次状态变化」解耦。
     */
    private var requestedPageTicket by mutableIntStateOf(0)

    /**
     * 预测性返回开关的当前值（Activity 级）。
     *
     * 为什么不只放在 setContent 里：onSaveInstanceState 要把它写进 Bundle ——
     * 组合内部的状态在进程被回收后就没了，恢复时必须从 Bundle 或偏好里读回来。
     */
    private var predictiveBackEnabled by mutableStateOf(true)

    /**
     * 应用是否处于前台（onResume 之后、onPause 之前）。
     *
     * 为什么需要这个状态：监控页的 5 秒快速采样跑在 `LaunchedEffect` 里，而 Compose 在
     * Activity 退到后台时**不会销毁组合** —— 循环会一直转下去，每 5 秒一次的
     * `NetPilot.sampleNow()` 就是一次完整的 HTTP 探测（DNS + TCP 建连 + 首字节），
     * 屏幕关着照跑。把「是否在前台」显式交给页面，不可见时自然停下。
     */
    private var isForeground by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        // 回到前台时重新检测 Root（用户可能刚刚授予权限）。
        XposedServiceManager.checkRoot()
    }

    override fun onPause() {
        // 先落状态再交给 super：onPause 之后组合随时可能被系统冻结或停止。
        isForeground = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyRequestedPage(intent)
    }

    /**
     * 把 intent 里的 [EXTRA_PAGE] 写进跳页状态。
     *
     * onCreate 与 onNewIntent 读的是同一个 EXTRA，收敛在一处免得两边的判断走样；
     * 负值表示「本次不指定页」，此时不写状态也不动票号，否则会把上一次的请求
     * 当成新请求再执行一遍。
     */
    private fun applyRequestedPage(intent: Intent?) {
        val page = intent?.getIntExtra(EXTRA_PAGE, -1) ?: -1
        if (page < 0) return
        requestedPage = page
        requestedPageTicket++
    }

    /**
     * 设置窗口的预测性返回开关。
     *
     * 为什么走反射：setEnableOnBackInvokedCallback 是 API 33 的方法，minSdk 正好也是 33，
     * 编译期本来就能直接引用；但厂商可能裁剪或改写这个方法，反射 + runCatching 让它安静降级成
     * 「没有预览动画」—— 返回手势本身不受影响，也不会在类校验阶段崩。
     */
    private fun applyPredictiveBack(enabled: Boolean) {
        runCatching {
            val method = android.view.Window::class.java.getMethod(
                "setEnableOnBackInvokedCallback",
                Boolean::class.javaPrimitiveType,
            )
            method.invoke(window, enabled)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // 组合内部的开关值在进程被回收后不可恢复，交给系统 Bundle 带过去。
        outState.putBoolean(STATE_PREDICTIVE_BACK, predictiveBackEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyRequestedPage(intent)
        window.isNavigationBarContrastEnforced = false

        val savedSettings = AppSettings.load(this)

        // 覆盖 XML 主题的窗口背景，兼容「系统浅色但应用内手动强制深色」的情况，避免启动白屏闪烁。
        applyWindowBackground(savedSettings.themeMode)

        // 预测性返回：优先用 Bundle 里的值（进程被回收后重建），否则用偏好。
        // 必须赶在 setContent 之前设好，否则首帧还会先按系统默认走一遍。
        predictiveBackEnabled = savedInstanceState?.getBoolean(STATE_PREDICTIVE_BACK, savedSettings.predictiveBack)
            ?: savedSettings.predictiveBack
        applyPredictiveBack(predictiveBackEnabled)

        setContent {
            var themeMode by remember {
                mutableStateOf(
                    try { ColorSchemeMode.valueOf(savedSettings.themeMode) }
                    catch (_: Exception) { ColorSchemeMode.System }
                )
            }
            var bottomBarStyle by remember { mutableStateOf(savedSettings.bottomBarStyle) }
            var railStyle by remember { mutableStateOf(savedSettings.railStyle) }
            var predictiveBack by remember { mutableStateOf(predictiveBackEnabled) }
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

            // 自动更新：只在应用被打开时查一次，关掉「自动检查更新」就完全不查。
            // 刻意不做后台轮询 —— 这个工程这一轮正是在削后台唤醒，新模组不能反过来往里加项。
            var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
            LaunchedEffect(Unit) {
                if (AppSettings.load(this@MainActivity).checkUpdateOnLaunch) {
                    UpdateChecker.checkForUpdate(this@MainActivity)
                        .onSuccess { info -> if (info.hasUpdate) updateInfo = info }
                }
            }

            // 网络质量降级默认开启，但监控循环挂在前台服务上：冷启动时若开关开着而服务
            // 没跑，界面会显示「已开启」却什么都不做 —— 正是本门面一直在避免的假状态。
            // 这里补一次对账。刻意放在 Activity 的 LaunchedEffect 而不是 Application.onCreate：
            // targetSdk 34 起从后台起前台服务就会被 ForegroundServiceStartNotAllowedException
            // 拒掉（36 只会更严），Activity 起来时不受这条限制。
            LaunchedEffect(Unit) {
                val appContext = this@MainActivity.applicationContext
                // 用户按过通知栏「停止」就不对账：那是运行时意图而不是配置，
                // 冷启动时把它撤销掉，等于「停止」按钮只在本次进程内有效。
                if (NetPilot.autoDowngradeEnabled() &&
                    NetPilot.servicesEnabled(appContext) &&
                    !MonitorSettings.userStopped(appContext) &&
                    !NetPilot.monitorRunning()
                ) {
                    NetPilot.setMonitorOnly(appContext, true)
                }
            }

            fun persistState() {
                // 为什么先 load 再 copy：这里只该覆盖界面上这几个偏好。若直接构造一个新的
                // AppSettings，其它字段会退回 data class 默认值 —— 用户在设置页刚改的
                // 「自动检查更新」会在下次改主题时被悄悄重置。
                AppSettings.save(
                    this@MainActivity,
                    AppSettings.load(this@MainActivity).copy(
                        themeMode = themeMode.name,
                        bottomBarStyle = bottomBarStyle,
                        railStyle = railStyle,
                        predictiveBack = predictiveBack,
                        isBlurEnabled = isBlurEnabled,
                        language = LocaleHelper.getSavedLanguage(this@MainActivity).code,
                    )
                )
            }

            // 顶层的整窗图层状态：玻璃弹层按窗口坐标绘制，必须画在所有 chrome 之上，
            // 否则会被底栏/侧栏盖住。页面用 TopLayer { } 把内容抬到这里。
            val topLayerState = remember { TopLayerState() }

            AppTheme(themeMode = themeMode) {
                // 与模板一致：全应用只在这里放一个「只负责弹层」的外层 Scaffold
                // （无顶栏/底栏、不消费 insets），它同时包住 pager、悬浮底栏与宽屏侧栏，
                // 因此它的 popupHost 在绘制顺序上位于这些导航 chrome 之上。
                // 弹层统一用 renderInRootScaffold = true 注册到这里，就天然「永远盖在
                // 底栏/侧栏上面」，既不会被底栏遮挡，也不需要额外的避让垫高。
                CompositionLocalProvider(LocalTopLayer provides topLayerState) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets(0),
                        ) {
                            MainScreen(
                                // 前台状态只能由 Activity 传进来：MainScreen 是文件里的顶层函数，
                                // 看不到 MainActivity 的实例成员，读不到 isForeground。
                                isForeground = isForeground,
                                themeMode = themeMode,
                                bottomBarStyle = bottomBarStyle,
                                railStyle = railStyle,
                                predictiveBack = predictiveBack,
                                isBlurEnabled = isBlurEnabled,
                                requestedPage = requestedPage,
                                requestedPageTicket = requestedPageTicket,
                                onThemeModeChange = { themeMode = it; persistState() },
                                onBottomBarStyleChange = { bottomBarStyle = it; persistState() },
                                onRailStyleChange = { railStyle = it; persistState() },
                                onPredictiveBackChange = {
                                    predictiveBack = it
                                    predictiveBackEnabled = it
                                    applyPredictiveBack(it)
                                    persistState()
                                },
                                onBlurEnabledChange = { isBlurEnabled = it; persistState() },
                            )
                        }
                        // 必须排在 Scaffold（含 pager/底栏/侧栏）之后：glass 弹层按窗口坐标绘制，
                        // 放在前面就会被这些 chrome 盖住。
                        TopLayerHost(state = topLayerState)
                    }
                }
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

            // 自动更新提示：权限相关对话框与首次引导都不显示时才渲染，避免同一帧叠两个
            // WindowDialog —— 和上面首次引导的排队方式一致。
            if (permissionPrompt == null && !showFirstLaunchGuide) {
                updateInfo?.let { info ->
                    UpdateDialog(
                        info = info,
                        onDismissRequest = { updateInfo = null },
                        onConfirm = {
                            updateInfo = null
                            // 交给系统浏览器：不申请 REQUEST_INSTALL_PACKAGES，也不做静默下载。
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    /** 应用是否在前台。监控页据此决定要不要开 5 秒快采。 */
    isForeground: Boolean,
    themeMode: ColorSchemeMode,
    bottomBarStyle: BottomBarStyle,
    railStyle: RailStyle,
    predictiveBack: Boolean,
    isBlurEnabled: Boolean,
    /** 外部请求跳转到的页（-1 = 不指定），见 MainActivity.EXTRA_PAGE。 */
    requestedPage: Int = -1,
    /** 跳页请求的票号：同页重复请求靠它才能再触发一次翻页，见 MainActivity.requestedPageTicket。 */
    requestedPageTicket: Int = 0,
    onThemeModeChange: (ColorSchemeMode) -> Unit,
    onBottomBarStyleChange: (BottomBarStyle) -> Unit,
    onRailStyleChange: (RailStyle) -> Unit,
    onPredictiveBackChange: (Boolean) -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val startPage = requestedPage.coerceIn(0, 4)
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { 5 })
    var selectedIndex by remember { mutableIntStateOf(startPage) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var homeRefreshKey by remember { mutableIntStateOf(0) }
    val searchState = remember { AppSearchState() }
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

    val isWideScreen = shouldShowSplitPane()
    // 侧栏只在宽屏且用户选了 Top/Left 时出现；None 表示继续用底部栏。
    val useRail = isWideScreen && railStyle.isEnabled

    val expandSidebar = railStyle == RailStyle.Left
    val sidebarState = rememberGlassNavigationRailState(initialExpanded = expandSidebar)
    LaunchedEffect(expandSidebar) {
        sidebarState.expanded = expandSidebar
    }
    // 侧栏展开态的宽度是库给的常量，内容让出的距离直接用它，不在运行时测量子组件。
    val sidebarWidth = GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth
    val railItems = items.mapIndexed { index, label ->
        GlassNavigationItem(icon = icons[index], label = label)
    }

    val onItemSelected: (Int) -> Unit = select@{ index ->
        searchState.close()
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

    // 从快速操作页跳进来（或本 Activity 已在栈里走 onNewIntent）时同步翻页。
    // 用 scrollToPage 而不是 springAnimate：这是「外部指定」而不是用户手势翻页，不需要动画。
    // 重组键用票号而不是目标页：连点同一页时 requestedPage 结构相等不会触发重组，
    // 只有票号变了，这次请求才会被真正执行一遍。
    LaunchedEffect(requestedPageTicket) {
        val target = requestedPage
        if (target in 0..4 && pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
            selectedIndex = target
            homeRefreshKey++
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
                        bottomBarStyle = bottomBarStyle,
                        onBottomBarStyleChange = onBottomBarStyleChange,
                        railStyle = railStyle,
                        onRailStyleChange = onRailStyleChange,
                        predictiveBack = predictiveBack,
                        onPredictiveBackChange = onPredictiveBackChange,
                        isBlurEnabled = isBlurEnabled,
                        onBlurEnabledChange = onBlurEnabledChange,
                        extraBottomPadding = navBarHeight,
                    )
                    2 -> MonitorPageView(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                        // 只有「本页就是当前页」且「应用在前台」时才开 5 秒快采。
                        // beyondViewportPageCount = 1 会把相邻页一起组合出来，切到别的
                        // 标签页并不会取消这里的 LaunchedEffect；应用退到后台时组合更是
                        // 整个活着 —— 不显式收口，后台就会一直按 5 秒一轮做完整 HTTP 探测。
                        // 页面可见时的行为与改动前完全一致。
                        liveSampling = isForeground && pagerState.currentPage == page,
                    )
                    3 -> LogPageView(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (useRail) {
            val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                    pagerContent(navigationBarBottom, PaddingValues(start = sidebarWidth))
                }
                // 模糊、边缘高光与展开/折叠动画都由 GlassNavigationRail 自己负责；
                // backdrop 给 null 表示用户关了模糊，此时玻璃面用不透明底色。
                GlassNavigationRail(
                    items = railItems,
                    selectedIndex = selectedIndex,
                    onSelect = onItemSelected,
                    backdrop = if (blurActive) backdrop else null,
                    state = sidebarState,
                )
            }
        } else {
            Scaffold(
                bottomBar = {
                    BottomNavigationBar(
                        style = bottomBarStyle,
                        items = items,
                        icons = icons,
                        selectedIndex = selectedIndex,
                        backdrop = backdrop,
                        blurActive = blurActive,
                        onItemSelected = onItemSelected,
                        onSearchClick = if (selectedIndex == 1) searchState::open else null,
                    )
                }
            ) { globalPadding ->
                pagerContent(globalPadding.calculateBottomPadding(), PaddingValues(0.dp))
            }
        }

    val searchListState = rememberLazyListState()
    AppSearchOverlay(
        expanded = searchState.expanded,
        onDismissRequest = { searchState.close() },
        label = stringResource(R.string.search_hint),
        blurEnabled = blurActive,
        scrollOffset = { searchListState.firstVisibleItemScrollOffset.toFloat() },
    ) { query ->
        LaunchedEffect(query) { searchListState.scrollToItem(0) }
        AppSearchResults(
            query = query,
            listState = searchListState,
            onPick = {
                searchState.close()
                onItemSelected(1)
            },
        )
    }
    }
}

@Composable
private fun BottomNavigationBar(
    style: BottomBarStyle,
    items: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selectedIndex: Int,
    backdrop: LayerBackdrop?,
    blurActive: Boolean,
    onItemSelected: (Int) -> Unit,
    onSearchClick: (() -> Unit)? = null,
) {
    when (style) {
        BottomBarStyle.IOS -> {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                IosLiquidGlassNavigationBar(
                    items = items,
                    icons = icons,
                    selectedIndex = selectedIndex,
                    onItemClick = onItemSelected,
                    backdrop = if (blurActive) backdrop else null,
                    isBlurActive = blurActive,
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.displayCutout.union(WindowInsets.navigationBars)
                                .only(WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = 24.dp)
                        .glassNavigationBarWidth(items.size, trailingActionWidth = 0.dp),
                )
            }
        }
        BottomBarStyle.HyperOS -> {
            val glassItems = items.mapIndexed { index, label ->
                GlassNavigationItem(icon = icons[index], label = label)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                GlassNavigationBar(
                    items = glassItems,
                    selectedIndex = selectedIndex,
                    onSelect = onItemSelected,
                    backdrop = if (blurActive) backdrop else null,
                    trailingAction = onSearchClick?.let { openSearch ->
                        {
                            GlassIconButton(
                                onClick = openSearch,
                                backdrop = if (blurActive) backdrop else null,
                                size = GlassNavigationBarDefaults.Height,
                                material = GlassNavigationBarDefaults.material(),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Search,
                                    contentDescription = stringResource(R.string.search_hint),
                                    modifier = Modifier.size(28.dp),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .glassNavigationBarWidth(items.size),
                )
            }
        }
    }
}

private val NavigationBarCapsuleMaxWidth = 344.dp

private val NavigationBarCapsuleMaxAvailable = 400.dp

private val NavigationBarItemSpacing = 10.dp

private val NavigationBarSpacingPadding = 6.dp

private fun Modifier.glassNavigationBarWidth(
    itemCount: Int,
    trailingActionWidth: Dp = GlassNavigationBarDefaults.Height + GlassNavigationBarDefaults.ActionSpacing,
): Modifier = layout { measurable, constraints ->
    val actionWidth = trailingActionWidth.roundToPx()
    val extraSpacing = (NavigationBarItemSpacing * (itemCount - 1) - NavigationBarSpacingPadding).roundToPx()
    val available = constraints.maxWidth - actionWidth + extraSpacing
    val width = if (constraints.hasBoundedWidth && available > NavigationBarCapsuleMaxAvailable.roundToPx()) {
        (NavigationBarCapsuleMaxWidth.roundToPx() + actionWidth).coerceIn(constraints.minWidth, constraints.maxWidth)
    } else {
        constraints.maxWidth
    }
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}
