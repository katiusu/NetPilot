package com.katiusu.netpilot.ui.screen.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.monitor.MonitorSettings
import com.katiusu.netpilot.ui.screen.datacard.DataCardActivity
import com.katiusu.netpilot.ui.util.BlurredBar
import com.katiusu.netpilot.ui.util.blurSource
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import com.katiusu.netpilot.ui.util.rememberBlurState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 主页仪表盘：一眼看清「现在是什么制式、信号怎么样、有没有在降级、走的是哪条特权通道」，
 * 并把手动切换、立即检测这些高频操作放在同一屏。
 *
 * 所有状态都从 [MonitorEngine] 的 StateFlow 直接 collect，不做本地轮询，
 * 保证界面显示的永远是内核的真实状态，不会出现「显示在监控其实早就停了」。
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
    val downgrade by MonitorEngine.downgrade.collectAsState()
    val running by MonitorEngine.running.collectAsState()
    val channelLabel by MonitorEngine.channelLabel.collectAsState()

    var modeValue by remember { mutableIntStateOf(-1) }
    var channelDetail by remember { mutableStateOf("") }
    var autoEnabled by remember { mutableStateOf(NetPilot.autoDowngradeEnabled()) }
    var simTitle by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val interval = MonitorSettings.thresholds().monitorIntervalSec

    fun refreshAll() {
        autoEnabled = NetPilot.autoDowngradeEnabled()
        simTitle = NetPilot.sims(context)
            .firstOrNull { it.isDefaultData }?.title
            ?: NetPilot.sims(context).firstOrNull()?.title.orEmpty()
        scope.launch {
            modeValue = NetPilot.currentModeValue(NetPilot.defaultDataSubId())
            channelDetail = NetPilot.channelSummary()
        }
    }

    LaunchedEffect(refreshKey) { refreshAll() }

    fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    fun applyPreset(preset: String) {
        if (busy) return
        busy = true
        scope.launch {
            val subId = NetPilot.defaultDataSubId()
            val ok = NetPilot.applyPreset(subId, preset)
            modeValue = NetPilot.currentModeValue(subId)
            busy = false
            toast(if (ok) R.string.np_home_result_ok else R.string.np_home_result_fail)
        }
    }

    val notSampled = stringResource(R.string.np_home_not_sampled)
    val signalText = if (snapshot == null) {
        notSampled
    } else {
        val s = snapshot!!
        buildString {
            append(s.networkType)
            s.rsrp?.let { append(" · RSRP ").append(it).append(" dBm") }
            s.sinr?.let { append(" · SINR ").append(it).append(" dB") }
        }
    }
    val latencyText = snapshot?.pingMs?.let { "$it ms" }
        ?: if (snapshot == null) notSampled else stringResource(R.string.np_home_idle)
    val downgradeText = when {
        downgrade.active -> stringResource(R.string.np_home_downgraded)
        running -> stringResource(R.string.np_home_normal)
        else -> stringResource(R.string.np_home_idle)
    }

    Scaffold(
        topBar = {
            BlurredBar(hazeState, blurActive, scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.app_name),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
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
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        SmallTitle(text = stringResource(R.string.np_home_section_status))
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Column {
                                BasicComponent(
                                    title = stringResource(R.string.np_home_mode),
                                    summary = NetworkMode.labelOf(modeValue),
                                )
                                BasicComponent(
                                    title = stringResource(R.string.np_home_signal),
                                    summary = signalText,
                                )
                                BasicComponent(
                                    title = stringResource(R.string.np_home_latency),
                                    summary = latencyText,
                                )
                                BasicComponent(
                                    title = stringResource(R.string.np_home_downgrade),
                                    summary = downgradeText,
                                )
                                BasicComponent(
                                    title = stringResource(R.string.np_home_channel),
                                    summary = channelLabel,
                                )
                                BasicComponent(
                                    title = stringResource(R.string.np_home_sim_default),
                                    summary = simTitle.ifBlank {
                                        stringResource(R.string.np_dc_no_sim)
                                    },
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.np_home_section_switch))
                        SwitchPreference(
                            title = stringResource(R.string.np_fake5g_enabled_title),
                            summary = if (autoEnabled) {
                                stringResource(R.string.np_home_auto_summary_on, interval)
                            } else {
                                stringResource(R.string.np_home_auto_summary_off)
                            },
                            checked = autoEnabled,
                            onCheckedChange = { on ->
                                autoEnabled = on
                                NetPilot.setAutoDowngrade(context, on)
                                refreshAll()
                            },
                        )

                        SmallTitle(text = stringResource(R.string.np_home_section_actions))
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        text = stringResource(R.string.np_home_preset_5g),
                                        onClick = { applyPreset("5g") },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        text = stringResource(R.string.np_home_preset_5g_only),
                                        onClick = { applyPreset("5g_only") },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        text = stringResource(R.string.np_home_preset_4g_only),
                                        onClick = { applyPreset("4g_only") },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        text = stringResource(R.string.np_home_sample),
                                        onClick = {
                                            if (busy) return@TextButton
                                            busy = true
                                            scope.launch {
                                                NetPilot.sampleNow(context)
                                                busy = false
                                                toast(R.string.np_home_result_ok)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        text = stringResource(R.string.np_home_lock_lte),
                                        onClick = {
                                            if (busy) return@TextButton
                                            busy = true
                                            scope.launch {
                                                val on = !NetworkMode.labelOf(modeValue)
                                                    .startsWith("仅 4G")
                                                val ok = NetPilot.lockLte(context, on)
                                                modeValue = NetPilot.currentModeValue(
                                                    NetPilot.defaultDataSubId()
                                                )
                                                busy = false
                                                toast(
                                                    if (ok) R.string.np_home_result_ok
                                                    else R.string.np_home_result_fail
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        text = stringResource(R.string.np_home_open_monitor),
                                        onClick = { onNavigate(2) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        text = stringResource(R.string.np_home_open_log),
                                        onClick = { onNavigate(3) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    text = stringResource(R.string.np_home_open_datacard),
                                    onClick = {
                                        context.startActivity(
                                            Intent(context, DataCardActivity::class.java)
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.np_home_section_channel))
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                MiuixText(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = channelDetail.ifBlank {
                                        stringResource(R.string.np_home_channel_none)
                                    },
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        text = stringResource(R.string.np_home_refresh),
                                        onClick = {
                                            scope.launch {
                                                NetPilot.refreshChannel()
                                                channelDetail = NetPilot.channelSummary()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        text = stringResource(R.string.np_home_shizuku),
                                        onClick = {
                                            val ok = NetPilot.requestShizukuPermission()
                                            toast(
                                                if (ok) R.string.np_home_result_ok
                                                else R.string.np_home_result_fail
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
