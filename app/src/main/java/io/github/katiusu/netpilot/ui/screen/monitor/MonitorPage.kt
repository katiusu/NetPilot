package io.github.katiusu.netpilot.ui.screen.monitor

import android.content.Context
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.mode.NetworkMode
import io.github.katiusu.netpilot.core.monitor.DowngradeReason
import io.github.katiusu.netpilot.core.monitor.MonitorEngine
import io.github.katiusu.netpilot.core.monitor.MonitorPhase
import io.github.katiusu.netpilot.core.monitor.SinrUnavailableReason
import io.github.katiusu.netpilot.ui.util.BlurredBar
import io.github.katiusu.netpilot.ui.util.blurSource
import io.github.katiusu.netpilot.ui.util.pageScrollModifiers
import io.github.katiusu.netpilot.ui.util.rememberBlurState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.katiusu.netpilot.ui.component.AppTopAppBar
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val FAST_SAMPLE_INTERVAL_MS = 5_000L

private fun isScreenInteractive(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
}.getOrDefault(true)

private val CLOCK_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
}

private fun clockText(timeMs: Long): String = CLOCK_FORMAT.get()!!.format(Date(timeMs))

@Composable
fun MonitorPageView(
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
    liveSampling: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    val hazeState = rememberBlurState()
    val blurActive = isBlurEnabled && hazeState != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val running by MonitorEngine.running.collectAsState()
    val snapshot by MonitorEngine.snapshot.collectAsState()
    val judgement by MonitorEngine.judgement.collectAsState()
    val downgrade by MonitorEngine.downgrade.collectAsState()
    val lastTickAt by MonitorEngine.lastTickAt.collectAsState()
    val channelLabel by MonitorEngine.channelLabel.collectAsState()

    val thresholds = NetPilot.thresholds()

    var autoEnabled by remember { mutableStateOf(NetPilot.autoDowngradeEnabled()) }
    var channelDetail by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(channelLabel) {
        channelDetail = runCatching { NetPilot.channelSummary() }.getOrDefault("")
    }

    LaunchedEffect(liveSampling) {
        if (!liveSampling) return@LaunchedEffect
        while (isActive) {
            val state = MonitorEngine.downgrade.value
            val phaseNow = state.phase(System.currentTimeMillis(), NetPilot.thresholds())
            val countingPhase =
                phaseNow == MonitorPhase.RECOVERING || phaseNow == MonitorPhase.ROLLBACK
            if (!countingPhase && isScreenInteractive(context)) {
                runCatching { NetPilot.sampleNow(context) }
            }
            autoEnabled = NetPilot.autoDowngradeEnabled()
            delay(FAST_SAMPLE_INTERVAL_MS)
        }
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun doSample() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = runCatching { NetPilot.sampleNow(context) }.isSuccess
            busy = false
            toast(
                context.getString(
                    if (ok) R.string.monitor_toast_sampled else R.string.monitor_toast_sampled_fail
                )
            )
        }
    }

    fun doReset() {
        if (busy) return
        busy = true
        NetPilot.resetDowngradeState(context)
        busy = false
        toast(context.getString(R.string.monitor_toast_reset))
    }

    fun doRefreshChannel() {
        if (busy) return
        busy = true
        scope.launch {
            val label = runCatching { NetPilot.refreshChannel() }
                .getOrDefault(context.getString(R.string.monitor_channel_none))
            channelDetail = runCatching { NetPilot.channelSummary() }.getOrDefault("")
            busy = false
            toast(context.getString(R.string.monitor_toast_channel, label))
        }
    }

    fun doRequestShizuku() {
        if (busy) return
        busy = true
        val ok = runCatching { NetPilot.requestShizukuPermission() }.getOrDefault(false)
        busy = false
        toast(
            context.getString(
                if (ok) R.string.monitor_toast_shizuku_ok else R.string.monitor_toast_shizuku_fail
            )
        )
    }

    val knowNow = System.currentTimeMillis()
    val phase = downgrade.phase(knowNow, thresholds)
    val cooldownRemainSec = if (downgrade.active && downgrade.degradedAtMs > 0L) {
        ((downgrade.degradedAtMs + thresholds.cooldownSec * 1000L - knowNow) / 1000L)
            .coerceAtLeast(0L)
    } else {
        0L
    }

    val unknown = stringResource(R.string.monitor_value_unknown)
    val noneText = stringResource(R.string.monitor_value_none)
    val noResponse = stringResource(R.string.monitor_value_no_response)
    val boolOn = stringResource(R.string.monitor_value_bool_on)
    val boolOff = stringResource(R.string.monitor_value_bool_off)

    val cooldownText = when {
        !downgrade.active -> stringResource(R.string.monitor_cooldown_none)
        cooldownRemainSec > 0L -> durationText(cooldownRemainSec)
        else -> noneText
    }

    val lastTickText = if (lastTickAt <= 0L) {
        stringResource(R.string.monitor_last_tick_never)
    } else {
        val elapsedSec = ((knowNow - lastTickAt) / 1000L).coerceAtLeast(0L)
        val clock = clockText(lastTickAt)
        if (elapsedSec <= 1L) {
            stringResource(R.string.monitor_last_tick_just_now, clock)
        } else {
            stringResource(R.string.monitor_last_tick_value, clock, elapsedSec)
        }
    }

    val snap = snapshot
    val sourceText = if (snap == null) {
        stringResource(R.string.monitor_source_none)
    } else if (snap.isWifi) {
        stringResource(
            R.string.monitor_source_wifi,
            snap.wifiSsid?.let { stringResource(R.string.monitor_source_wifi_ssid, it) }.orEmpty(),
        )
    } else {
        stringResource(R.string.monitor_source_cellular, snap.slot, snap.subId)
    }

    Scaffold(
        topBar = {
            BlurredBar(hazeState, blurActive, scrollBehavior) {
                AppTopAppBar(
                    title = stringResource(R.string.monitor_page_title),
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
                    MonitorSection(title = stringResource(R.string.monitor_section_switch)) {
                        SwitchPreference(
                            checked = autoEnabled,
                            onCheckedChange = { checked ->
                                autoEnabled = checked
                                NetPilot.setAutoDowngrade(context, checked)
                            },
                            title = stringResource(R.string.monitor_switch_title),
                            summary = stringResource(
                                if (autoEnabled) {
                                    R.string.monitor_switch_summary_on
                                } else {
                                    R.string.monitor_switch_summary_off
                                }
                            ),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_loop_title),
                            summary = stringResource(
                                if (running) R.string.monitor_loop_on else R.string.monitor_loop_off
                            ),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_phase_title),
                            summary = phaseText(phase),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_last_tick_title),
                            summary = lastTickText,
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_cooldown_title),
                            summary = cooldownText,
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_recovery_title),
                            summary = stringResource(
                                R.string.monitor_recovery_value,
                                downgrade.recoveryCount,
                                thresholds.recoveryCount,
                            ),
                        )
                    }
                }

                item {
                    MonitorSection(title = stringResource(R.string.monitor_section_signal)) {
                        if (snap == null) {
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_section_signal),
                                summary = stringResource(R.string.monitor_last_tick_never),
                            )
                        } else {
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_signal_network),
                                summary = snap.networkType,
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_signal_raw),
                                summary = stringResource(
                                    R.string.monitor_signal_raw_value,
                                    snap.rawNetworkType,
                                ),
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_signal_carrier),
                                summary = snap.operatorName.ifBlank { unknown },
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_signal_rsrp),
                                summary = snap.rsrp?.let {
                                    stringResource(R.string.monitor_value_dbm, it)
                                } ?: unknown,
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_signal_sinr),
                                summary = snap.sinr?.let {
                                    stringResource(R.string.monitor_value_db, it)
                                } ?: unknown,
                            )
                            if (snap.sinr != null) {
                                val sinrSource = snap.sinrSource
                                if (!sinrSource.isNullOrBlank()) {
                                    MonitorNoteLine(
                                        text = stringResource(
                                            R.string.q_sinr_source_note,
                                            sinrSource,
                                        ),
                                    )
                                }
                            } else {
                                MonitorNoteLine(
                                    text = stringResource(R.string.q_sinr_unavailable_title) +
                                        "：" + stringResource(
                                        sinrReasonRes(
                                            snap.sinrReasonKind
                                                ?: SinrUnavailableReason.NOT_REPORTED,
                                        ),
                                    ),
                                )
                            }
                            MonitorInfoRow(
                                title = stringResource(
                                    R.string.monitor_signal_ping,
                                    thresholds.pingTarget
                                        .substringAfter("://")
                                        .substringBefore("/"),
                                ),
                                summary = snap.pingMs?.let {
                                    stringResource(R.string.monitor_value_ms, it)
                                } ?: snap.pingError?.let {
                                    stringResource(R.string.q_ping_failed_short)
                                } ?: if (snap.probeSkipped) {
                                    stringResource(R.string.monitor_value_ping_skipped)
                                } else {
                                    noResponse
                                },
                            )
                            val pingError = snap.pingError
                            if (!pingError.isNullOrBlank()) {
                                MonitorNoteLine(
                                    text = stringResource(R.string.q_ping_error_detail, pingError),
                                )
                            }
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_signal_source),
                                summary = sourceText,
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_signal_snapshot_time),
                                summary = if (snap.timeMs > 0L) clockText(snap.timeMs) else unknown,
                            )
                        }
                    }
                }

                item {
                    MonitorSection(title = stringResource(R.string.monitor_section_judge)) {
                        val judge = judgement
                        if (judge == null) {
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_section_judge),
                                summary = stringResource(R.string.monitor_judge_none),
                            )
                        } else {
                            val verdict = stringResource(
                                if (judge.fake) {
                                    R.string.monitor_judge_fake
                                } else {
                                    R.string.monitor_judge_real
                                }
                            )
                            val strength = stringResource(
                                if (judge.isStrong) {
                                    R.string.monitor_judge_strong_ok
                                } else {
                                    R.string.monitor_judge_weak
                                }
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_section_judge),
                                summary = "$verdict · $strength",
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.q_judge_reason_title),
                                summary = when (judge.reason) {
                                    DowngradeReason.FAKE_FULL_BAR -> stringResource(
                                        R.string.q_judge_reason_full_bar,
                                    )
                                    DowngradeReason.WEAK_SIGNAL -> stringResource(
                                        R.string.q_judge_reason_weak,
                                    )
                                    DowngradeReason.NONE -> noneText
                                },
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_judge_reasons),
                                summary = if (judge.reasons.isEmpty()) {
                                    noneText
                                } else {
                                    judge.reasons.joinToString("；")
                                },
                            )
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_judge_detail),
                                summary = judge.detail.ifBlank { noneText },
                            )
                        }
                        MonitorInfoRow(
                            title = stringResource(R.string.q_judge_ping_fail_title),
                            summary = stringResource(
                                R.string.q_judge_ping_fail_value,
                                if (thresholds.downgradeOnPingFail) boolOn else boolOff,
                            ),
                        )
                    }
                }

                item {
                    MonitorSection(title = stringResource(R.string.q_section_criteria)) {
                        MonitorCriteriaLines(thresholds)
                    }
                }

                item {
                    MonitorSection(title = stringResource(R.string.monitor_section_thresholds)) {
                        MiuixText(
                            text = stringResource(R.string.monitor_threshold_hint),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_rsrp),
                            summary = stringResource(
                                R.string.monitor_value_dbm,
                                thresholds.rsrpThreshold,
                            ) + " · " + stringResource(R.string.monitor_threshold_rsrp_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_sinr),
                            summary = stringResource(
                                R.string.monitor_value_db,
                                thresholds.sinrThreshold,
                            ) + " · " + stringResource(R.string.monitor_threshold_sinr_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_ping),
                            summary = stringResource(
                                R.string.monitor_value_ms,
                                thresholds.pingThresholdMs,
                            ) + " · " + stringResource(R.string.monitor_threshold_ping_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_weak_signal),
                            summary = (if (thresholds.downgradeOnWeakSignal) boolOn else boolOff) +
                                " · " +
                                stringResource(R.string.monitor_threshold_weak_signal_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_weak_rsrp),
                            summary = stringResource(
                                R.string.monitor_value_dbm,
                                thresholds.weakRsrpThreshold,
                            ) + " · " + stringResource(R.string.monitor_threshold_weak_rsrp_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_cooldown),
                            summary = durationText(thresholds.cooldownSec.toLong()) + " · " +
                                stringResource(R.string.monitor_threshold_cooldown_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_recovery),
                            summary = stringResource(
                                R.string.monitor_value_count,
                                thresholds.recoveryCount,
                            ) + " · " + stringResource(R.string.monitor_threshold_recovery_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_rollback),
                            summary = stringResource(
                                R.string.monitor_value_count,
                                thresholds.noNetRollbackCount,
                            ) + " · " + stringResource(R.string.monitor_threshold_rollback_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_interval),
                            summary = durationText(thresholds.monitorIntervalSec.toLong()) + " · " +
                                stringResource(R.string.monitor_threshold_interval_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_downgrade),
                            summary = NetworkMode.labelOf(thresholds.downgradeMode) + " · " +
                                stringResource(R.string.monitor_threshold_downgrade_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_lock_lte),
                            summary = NetworkMode.labelOf(thresholds.lockLteMode) + " · " +
                                stringResource(R.string.monitor_threshold_lock_lte_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_ping_fail),
                            summary = (if (thresholds.downgradeOnPingFail) boolOn else boolOff) +
                                " · " +
                                stringResource(R.string.monitor_threshold_ping_fail_summary),
                        )
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_threshold_endc),
                            summary = (if (thresholds.toggleEndc) boolOn else boolOff) + " · " +
                                stringResource(R.string.monitor_threshold_endc_summary),
                        )
                    }
                }

                item {
                    MonitorSection(title = stringResource(R.string.monitor_section_actions)) {
                        MonitorActionRow(
                            title = stringResource(R.string.monitor_action_sample),
                            summary = stringResource(R.string.monitor_action_sample_summary),
                            enabled = !busy,
                            onClick = { doSample() },
                        )
                        MonitorActionRow(
                            title = stringResource(R.string.monitor_action_reset),
                            summary = stringResource(R.string.monitor_action_reset_summary),
                            enabled = !busy,
                            onClick = { doReset() },
                        )
                        MonitorActionRow(
                            title = stringResource(R.string.monitor_action_refresh_channel),
                            summary = stringResource(R.string.monitor_action_refresh_channel_summary),
                            enabled = !busy,
                            onClick = { doRefreshChannel() },
                        )
                        MonitorActionRow(
                            title = stringResource(R.string.monitor_action_shizuku),
                            summary = stringResource(R.string.monitor_action_shizuku_summary),
                            enabled = !busy,
                            onClick = { doRequestShizuku() },
                        )
                    }
                }

                item {
                    MonitorSection(title = stringResource(R.string.monitor_section_channel)) {
                        MonitorInfoRow(
                            title = stringResource(R.string.monitor_channel_current),
                            summary = channelLabel.ifBlank {
                                stringResource(R.string.monitor_channel_none)
                            },
                        )
                        val detail = channelDetail
                        if (detail.isBlank()) {
                            MonitorInfoRow(
                                title = stringResource(R.string.monitor_channel_detail),
                                summary = stringResource(R.string.monitor_channel_detail_empty),
                            )
                        } else {
                            detail.split("；").forEach { line ->
                                MonitorInfoRow(
                                    title = stringResource(R.string.monitor_channel_detail),
                                    summary = line,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        SmallTitle(text = title)
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            Column(content = content)
        }
    }
}

@Composable
private fun MonitorInfoRow(title: String, summary: String? = null) {
    BasicComponent(title = title, summary = summary)
}

@Composable
private fun MonitorActionRow(
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun phaseText(phase: MonitorPhase): String = stringResource(
    when (phase) {
        MonitorPhase.OFF -> R.string.monitor_phase_off
        MonitorPhase.WATCHING -> R.string.monitor_phase_watching
        MonitorPhase.DOWNGRADED_COOLDOWN -> R.string.monitor_phase_cooldown
        MonitorPhase.RECOVERING -> R.string.monitor_phase_recovering
        MonitorPhase.ROLLBACK -> R.string.monitor_phase_rollback
    }
)
