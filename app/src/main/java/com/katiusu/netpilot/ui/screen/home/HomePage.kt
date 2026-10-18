package com.katiusu.netpilot.ui.screen.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katiusu.netpilot.MainActivity
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.monitor.MonitorSettings
import com.katiusu.netpilot.ui.component.SectionTitle
import com.katiusu.netpilot.ui.component.SettingsAction
import com.katiusu.netpilot.ui.component.SettingsActionWithArrow
import com.katiusu.netpilot.ui.screen.quick.QuickActionsActivity
import com.katiusu.netpilot.ui.util.BlurredBar
import com.katiusu.netpilot.ui.util.blurSource
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import com.katiusu.netpilot.ui.util.rememberBlurState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** 主页「状态卡」在运行/停止时用同一条强调色，避免整块卡片在深色主题下发亮。 */
private val RunningAccent = Color(0xFF3E9B4F)
private val StoppedAccent = Color(0xFF8A8A8A)

/**
 * 主页仪表盘（1.6.0 按参考工程 HyperIsland 3.2.1 的结构重写，MIT）。
 *
 * 布局：状态网格（1 张大卡 + 2 张数值卡）→ 可选告警卡 → 版本信息卡 → 入口卡（快速操作/
 * 数据卡/日志）→ 项目链接卡。原来摊在主页上的「立即检测 / 锁 LTE / 申请 Shizuku」等
 * 带副作用的按钮全部移进 [QuickActionsActivity]：主页只负责「看」和「进」。
 *
 * 状态一律从 [MonitorEngine] 的 StateFlow 收集，不本地轮询；制式读数仍然走
 * [NetPilot.currentModeValue]（有特权通道才读得到，读不到时返回 -1）。
 */
@Composable
fun HomePageView(
    isBlurEnabled: Boolean = true,
    refreshKey: Int = 0,
    extraBottomPadding: Dp = 0.dp,
    onNavigate: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    val hazeState = rememberBlurState()
    val blurActive = isBlurEnabled && hazeState != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val snapshot by MonitorEngine.snapshot.collectAsState()
    val running by MonitorEngine.running.collectAsState()
    val channelLabel by MonitorEngine.channelLabel.collectAsState()

    var modeValue by remember { mutableIntStateOf(-1) }
    var modeLoading by remember { mutableStateOf(true) }
    val interval = MonitorSettings.thresholds().monitorIntervalSec

    LaunchedEffect(refreshKey) {
        modeLoading = true
        modeValue = runCatching { NetPilot.currentModeValue(NetPilot.defaultDataSubId()) }
            .getOrDefault(-1)
        modeLoading = false
    }

    // 本工程没开 buildConfig（AGP 默认关），版本号从 PackageManager 读；
    // remember 一次即可，避免每次重组都走一遍 IPC。
    val appVersion = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${info.versionName ?: "?"} (${info.longVersionCode})"
    }
    val modeText = when {
        modeLoading -> "…"
        modeValue >= 0 -> NetworkMode.labelOf(modeValue)
        else -> "—"
    }
    val latencyText = snapshot?.pingMs?.let { "$it ms" } ?: "—"
    val needChannel = !modeLoading && modeValue < 0

    Scaffold(
        topBar = {
            BlurredBar(hazeState, blurActive, scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.app_name),
                    largeTitle = stringResource(R.string.app_name),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    modeLoading = true
                                    modeValue = runCatching {
                                        NetPilot.currentModeValue(NetPilot.defaultDataSubId())
                                    }.getOrDefault(-1)
                                    modeLoading = false
                                }
                            },
                        ) {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.np_home_refresh),
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = Modifier.blurSource(if (isBlurEnabled) hazeState else null)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                    ),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + extraBottomPadding,
                ),
            ) {
                item {
                    StatusGrid(
                        running = running,
                        appVersion = appVersion,
                        intervalSec = interval,
                        modeText = modeText,
                        latencyText = latencyText,
                        onOpenMonitor = { onNavigate(MainActivity.PAGE_MONITOR) },
                        onOpenQuick = { openQuick(context) },
                    )
                }

                if (needChannel) {
                    item { AlertCard(onOpenQuick = { openQuick(context) }) }
                }

                item {
                    SectionTitle(stringResource(R.string.np_home_section_status))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column {
                            InfoRow(
                                title = stringResource(R.string.np_home_info_system),
                                value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                            )
                            InfoRow(
                                title = stringResource(R.string.np_home_info_app),
                                value = appVersion,
                            )
                            InfoRow(
                                title = stringResource(R.string.np_home_info_channel),
                                value = channelLabel.ifBlank {
                                    stringResource(R.string.np_home_channel_none)
                                },
                            )
                            InfoRow(
                                title = stringResource(R.string.np_home_info_device),
                                value = Build.MODEL ?: stringResource(R.string.np_home_info_unknown),
                            )
                        }
                    }
                }

                item {
                    SectionTitle(stringResource(R.string.np_home_section_actions))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column {
                            SettingsActionWithArrow(
                                title = stringResource(R.string.np_home_action_quick),
                                summary = stringResource(R.string.np_home_action_quick_summary),
                            ) { openQuick(context) }
                            SettingsActionWithArrow(
                                title = stringResource(R.string.np_home_open_datacard),
                                summary = stringResource(R.string.np_home_action_datacard_summary),
                            ) {
                                context.startActivity(
                                    Intent(context, com.katiusu.netpilot.ui.screen.datacard.DataCardActivity::class.java)
                                )
                            }
                            SettingsActionWithArrow(
                                title = stringResource(R.string.np_home_action_log),
                                summary = stringResource(R.string.np_home_action_log_summary),
                            ) { onNavigate(MainActivity.PAGE_LOG) }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column {
                            SettingsAction(
                                title = stringResource(R.string.np_home_link_repo),
                                endIcon = MiuixIcons.Link,
                            ) { openUrl(context, GITHUB_REPO_URL) }
                            SettingsAction(
                                title = stringResource(R.string.np_home_link_changelog),
                                endIcon = MiuixIcons.Link,
                            ) { openUrl(context, GITHUB_RELEASES_URL) }
                            SettingsAction(
                                title = stringResource(R.string.np_home_link_issues),
                                endIcon = MiuixIcons.Link,
                            ) { openUrl(context, GITHUB_ISSUES_URL) }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * 1 张大状态卡 + 2 张数值卡。
 *
 * 宽度 >= 600dp（横屏/平板/折叠展开）时三卡横排等高；否则大卡在左、两张数值卡在右列，
 * 三卡同为正方形比例 —— 与参考工程一致，避免窄屏下三张卡挤成三条细长条。
 */
@Composable
private fun StatusGrid(
    running: Boolean,
    appVersion: String,
    intervalSec: Int,
    modeText: String,
    latencyText: String,
    onOpenMonitor: () -> Unit,
    onOpenQuick: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusCard(
                    running = running,
                    appVersion = appVersion,
                    intervalSec = intervalSec,
                    modifier = Modifier
                        .weight(1f)
                        .height(118.dp),
                    onClick = onOpenMonitor,
                )
                StatCard(
                    title = stringResource(R.string.np_home_mode),
                    value = modeText,
                    modifier = Modifier
                        .weight(1f)
                        .height(118.dp),
                    onClick = onOpenQuick,
                    accent = RunningAccent,
                )
                StatCard(
                    title = stringResource(R.string.np_home_latency),
                    value = latencyText,
                    modifier = Modifier
                        .weight(1f)
                        .height(118.dp),
                    onClick = onOpenMonitor,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusCard(
                    running = running,
                    appVersion = appVersion,
                    intervalSec = intervalSec,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                    onClick = onOpenMonitor,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        title = stringResource(R.string.np_home_mode),
                        value = modeText,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenQuick,
                        accent = RunningAccent,
                    )
                    StatCard(
                        title = stringResource(R.string.np_home_latency),
                        value = latencyText,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenMonitor,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    appVersion: String,
    intervalSec: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = if (running) RunningAccent else StoppedAccent
    Card(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .padding(end = 0.dp),
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(color = accent, radius = size.minDimension / 2f)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (running) R.string.np_home_monitoring else R.string.np_home_idle
                        ),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = appVersion,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (running) {
                    stringResource(R.string.np_home_status_running_summary, intervalSec)
                } else {
                    stringResource(R.string.np_home_idle)
                },
                fontSize = 12.sp,
                color = accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    accent: Color? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent ?: MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 拿不到制式时给出的告警卡：只解释原因并给一个入口，不做任何自动修复。 */
@Composable
private fun AlertCard(onOpenQuick: () -> Unit) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.np_home_alert_channel_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.np_home_alert_channel_message),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            SettingsActionWithArrow(
                title = stringResource(R.string.np_home_action_quick),
                summary = stringResource(R.string.np_quick_section_channel),
                onClick = onOpenQuick,
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    BasicComponent(
        title = title,
        summary = value,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    )
}

private fun openQuick(context: android.content.Context) {
    context.startActivity(Intent(context, QuickActionsActivity::class.java))
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private const val GITHUB_REPO_URL = "https://github.com/katiusu/NetPilot"
private const val GITHUB_RELEASES_URL = "https://github.com/katiusu/NetPilot/releases"
private const val GITHUB_ISSUES_URL = "https://github.com/katiusu/NetPilot/issues"
