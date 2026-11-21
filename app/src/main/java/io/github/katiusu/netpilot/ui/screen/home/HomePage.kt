package io.github.katiusu.netpilot.ui.screen.home

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.katiusu.netpilot.MainActivity
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.mode.NetworkMode
import io.github.katiusu.netpilot.core.monitor.MonitorEngine
import io.github.katiusu.netpilot.core.monitor.MonitorSettings
import io.github.katiusu.netpilot.ui.component.AppArrowPreference
import io.github.katiusu.netpilot.ui.component.AppLazyPage
import io.github.katiusu.netpilot.ui.component.AppSectionCard
import io.github.katiusu.netpilot.ui.component.sectionCardPadding
import io.github.katiusu.netpilot.ui.icons.CheckCircleOutlineIcon
import io.github.katiusu.netpilot.ui.icons.ErrorOutlineIcon
import io.github.katiusu.netpilot.ui.icons.RemoveCircleOutlineIcon
import io.github.katiusu.netpilot.ui.screen.datacard.DataCardActivity
import io.github.katiusu.netpilot.ui.screen.quick.QuickActionsActivity
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private val RunningAccent = Color(0xFF3E9B4F)
private val StoppedAccent = Color(0xFF8A8A8A)

@Composable
fun HomePageView(
    isBlurEnabled: Boolean = true,
    refreshKey: Int = 0,
    extraBottomPadding: Dp = 0.dp,
    onNavigate: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val snapshot by MonitorEngine.snapshot.collectAsState()
    val running by MonitorEngine.running.collectAsState()
    val channelLabel by MonitorEngine.channelLabel.collectAsState()
    val intervalSec = remember { MonitorSettings.thresholds().monitorIntervalSec }

    var localRefresh by remember { mutableIntStateOf(0) }
    var modeValue by remember { mutableIntStateOf(-1) }
    var modeLoading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshKey, localRefresh) {
        modeLoading = true
        modeValue = runCatching { NetPilot.currentModeValue(NetPilot.defaultDataSubId()) }
            .getOrDefault(-1)
        modeLoading = false
    }

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

    AppLazyPage(
        title = stringResource(R.string.tab_home),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        topBarActions = {
            IconButton(onClick = { localRefresh++ }) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.np_home_refresh),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
    ) {
        item {
            val statusColor = when {
                needChannel -> MiuixTheme.colorScheme.errorContainer
                running -> MiuixTheme.colorScheme.secondaryContainer
                else -> MiuixTheme.colorScheme.surfaceContainer
            }
            val iconTint = when {
                needChannel -> MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
                running -> RunningAccent
                else -> StoppedAccent
            }
            val statusIcon: ImageVector = when {
                needChannel -> ErrorOutlineIcon
                running -> CheckCircleOutlineIcon
                else -> RemoveCircleOutlineIcon
            }
            val statusText = if (running) {
                stringResource(R.string.np_home_status_running_summary, intervalSec)
            } else {
                stringResource(R.string.np_home_idle)
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .sectionCardPadding(top = 12.dp),
            ) {
                val wide = maxWidth >= 600.dp
                val cardsModifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                if (wide) {
                    Row(
                        modifier = cardsModifier,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusCard(
                            titleText = stringResource(
                                if (running) R.string.np_home_monitoring else R.string.np_home_idle
                            ),
                            versionText = appVersion,
                            statusText = statusText,
                            imageVector = statusIcon,
                            iconTint = iconTint,
                            statusColor = statusColor,
                            onClick = { onNavigate(MainActivity.PAGE_MONITOR) },
                            compact = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        CountCard(
                            title = stringResource(R.string.np_home_mode),
                            count = modeText,
                            onClick = { openQuick(context) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        CountCard(
                            title = stringResource(R.string.np_home_latency),
                            count = latencyText,
                            onClick = { onNavigate(MainActivity.PAGE_MONITOR) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                } else {
                    Row(
                        modifier = cardsModifier,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusCard(
                            titleText = stringResource(
                                if (running) R.string.np_home_monitoring else R.string.np_home_idle
                            ),
                            versionText = appVersion,
                            statusText = statusText,
                            imageVector = statusIcon,
                            iconTint = iconTint,
                            statusColor = statusColor,
                            onClick = { onNavigate(MainActivity.PAGE_MONITOR) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            CountCard(
                                title = stringResource(R.string.np_home_mode),
                                count = modeText,
                                onClick = { openQuick(context) },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                            Spacer(Modifier.height(12.dp))
                            CountCard(
                                title = stringResource(R.string.np_home_latency),
                                count = latencyText,
                                onClick = { onNavigate(MainActivity.PAGE_MONITOR) },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        }
                    }
                }
            }
        }

        if (needChannel) {
            item {
                AppSectionCard {
                    BasicComponent(
                        title = stringResource(R.string.np_home_alert_channel_title),
                        summary = stringResource(R.string.np_home_alert_channel_message),
                    )
                    AppArrowPreference(
                        title = stringResource(R.string.np_home_action_quick),
                        summary = stringResource(R.string.np_quick_section_channel),
                        onClick = { openQuick(context) },
                    )
                }
            }
        }

        item {
            AppSectionCard(title = stringResource(R.string.np_home_section_status)) {
                BasicComponent(
                    title = stringResource(R.string.np_home_info_system),
                    summary = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                )
                BasicComponent(
                    title = stringResource(R.string.np_home_info_app),
                    summary = appVersion,
                )
                BasicComponent(
                    title = stringResource(R.string.np_home_info_channel),
                    summary = channelLabel.ifBlank { stringResource(R.string.np_home_channel_none) },
                )
                BasicComponent(
                    title = stringResource(R.string.np_home_info_device),
                    summary = Build.MODEL ?: stringResource(R.string.np_home_info_unknown),
                )
            }
        }

        item {
            AppSectionCard(title = stringResource(R.string.np_home_section_actions)) {
                AppArrowPreference(
                    title = stringResource(R.string.np_home_action_quick),
                    summary = stringResource(R.string.np_home_action_quick_summary),
                    onClick = { openQuick(context) },
                )
                AppArrowPreference(
                    title = stringResource(R.string.np_home_open_datacard),
                    summary = stringResource(R.string.np_home_action_datacard_summary),
                    onClick = { context.startActivity(Intent(context, DataCardActivity::class.java)) },
                )
                AppArrowPreference(
                    title = stringResource(R.string.np_home_action_log),
                    summary = stringResource(R.string.np_home_action_log_summary),
                    onClick = { onNavigate(MainActivity.PAGE_LOG) },
                )
            }
        }

        item {
            val uriHandler = LocalUriHandler.current
            AppSectionCard {
                AppArrowPreference(
                    title = stringResource(R.string.np_home_link_repo),
                    summary = GITHUB_REPO_URL,
                    onClick = { runCatching { uriHandler.openUri(GITHUB_REPO_URL) } },
                )
                AppArrowPreference(
                    title = stringResource(R.string.np_home_link_changelog),
                    summary = GITHUB_RELEASES_URL,
                    onClick = { runCatching { uriHandler.openUri(GITHUB_RELEASES_URL) } },
                )
                AppArrowPreference(
                    title = stringResource(R.string.np_home_link_issues),
                    summary = GITHUB_ISSUES_URL,
                    onClick = { runCatching { uriHandler.openUri(GITHUB_ISSUES_URL) } },
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusCard(
    titleText: String,
    versionText: String,
    statusText: String,
    imageVector: ImageVector,
    iconTint: Color,
    statusColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val iconSize = if (compact) 100.dp else 170.dp
    val iconOffsetX = if (compact) 22.dp else 38.dp
    val iconOffsetY = if (compact) 26.dp else 45.dp
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(statusColor),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(iconOffsetX, iconOffsetY),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(iconSize),
                    imageVector = imageVector,
                    tint = iconTint,
                    contentDescription = null,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(all = 16.dp),
            ) {
                MiuixText(
                    modifier = Modifier.fillMaxWidth(),
                    text = titleText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                MiuixText(
                    modifier = Modifier.fillMaxWidth(),
                    text = versionText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    modifier = Modifier.fillMaxWidth(),
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CountCard(
    title: String,
    count: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        showIndication = true,
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            MiuixText(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            MiuixText(
                modifier = Modifier.fillMaxWidth(),
                text = count,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun openQuick(context: android.content.Context) {
    context.startActivity(Intent(context, QuickActionsActivity::class.java))
}

private const val GITHUB_REPO_URL = "https://github.com/katiusu/NetPilot"
private const val GITHUB_RELEASES_URL = "https://github.com/katiusu/NetPilot/releases"
private const val GITHUB_ISSUES_URL = "https://github.com/katiusu/NetPilot/issues"
