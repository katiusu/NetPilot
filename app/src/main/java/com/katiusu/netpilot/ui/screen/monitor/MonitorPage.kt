package com.katiusu.netpilot.ui.screen.monitor

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
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.monitor.DowngradeReason
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.monitor.MonitorPhase
import com.katiusu.netpilot.core.monitor.SinrUnavailableReason
import com.katiusu.netpilot.ui.util.BlurredBar
import com.katiusu.netpilot.ui.util.blurSource
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import com.katiusu.netpilot.ui.util.rememberBlurState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 监控页的本页快速采样间隔。
 *
 * 只影响「这个页面自己刷新得有多勤」，不会改动内核 [com.katiusu.netpilot.core.monitor.MonitorSettings]
 * 里那个常规采样间隔（默认 120 秒）——那个数由前台服务在用，这里动它就是越权。
 */
private const val FAST_SAMPLE_INTERVAL_MS = 5_000L

/**
 * 屏幕当前是否处于交互状态。
 *
 * 为什么页内采样需要自己判一次：`liveSampling` 是宿主通过重组传进来的，而 Compose 的重组
 * 要等下一帧（Recomposer 在 `withFrameNanos` 上等 VSYNC）。屏幕一关，系统就不再投递
 * VSYNC，宿主传来的 `false` 迟迟不会生效，可这个采样循环里的 `delay()` 走的是
 * `DefaultDelay`（不受帧约束），于是会继续每 5 秒跑一次**真实网络探测**。所以在循环内部
 * 每轮再确认一次屏幕状态，堵掉「关屏后照旧探测」这条路径。
 *
 * 读不到电源服务时返回 `true`（保守：宁可多采一次，也不要因为一次异常把界面刷新停掉）。
 * 判据与 [com.katiusu.netpilot.core.monitor.AutoDowngradeEngine] 里的同名判断一致。
 */
private fun isScreenInteractive(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
}.getOrDefault(true)

/** 与日志页同一套时间格式，避免两页显示同一时刻却长得不一样。 */
private val CLOCK_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
}

private fun clockText(timeMs: Long): String = CLOCK_FORMAT.get()!!.format(Date(timeMs))

/**
 * 监控页：把「网络质量自动降级」这条链路从开关一路摊到最底层的判定依据。
 *
 * 页面上的每个数都直接来自 [MonitorEngine] 的 StateFlow 或 [NetPilot] 门面，页面自己不缓存、
 * 不推算，所以显示的一定是内核的真实状态。
 *
 * @param isBlurEnabled 是否启用顶栏与背景的模糊（由外层主题决定）。
 * @param extraBottomPadding 额外的底部留白，给外层底部导航栏用。
 * @param liveSampling 是否开启页内 5 秒快速采样。默认开；宿主如果能准确知道
 *   「本页当前可见」，可以在不可见时传 false，避免在后台白白多跑采样。
 *   这个开关靠重组传达，而重组要等下一帧，所以它**单独不足以**在屏幕关闭时停掉采样；
 *   循环内部另有一道 [isScreenInteractive] 检查兜底，两者是并列关系，都要留。
 */
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

    // ---- 内核状态（全部直接 collect，不做本地快照） ----
    val running by MonitorEngine.running.collectAsState()
    val snapshot by MonitorEngine.snapshot.collectAsState()
    val judgement by MonitorEngine.judgement.collectAsState()
    val downgrade by MonitorEngine.downgrade.collectAsState()
    val lastTickAt by MonitorEngine.lastTickAt.collectAsState()
    val channelLabel by MonitorEngine.channelLabel.collectAsState()

    // 阈值来自 ConfigState，读它会自动参与重组：功能页改完，这里立刻跟着变。
    val thresholds = NetPilot.thresholds()

    var autoEnabled by remember { mutableStateOf(NetPilot.autoDowngradeEnabled()) }
    var channelDetail by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 通道自述跟着内核的通道标签走：内核探测出新结果就刷新一次。
    LaunchedEffect(channelLabel) {
        channelDetail = runCatching { NetPilot.channelSummary() }.getOrDefault("")
    }

    // ---- 页内快速采样 ----
    // 契约要求「页面可见时每 5 秒采一次」。这里加了一条安全阀：sampleNow() 会推进降级
    // 状态机（AutoDowngradeEngine.tick → applyTransition），而 RECOVERING / ROLLBACK
    // 两个阶段是靠「连续正常轮数」计数的，5 秒一轮会把原本几分钟的判定压成十几秒。
    // 所以这两个阶段跳过注入式采样，交给内核自己的间隔去走；其余阶段多采几次无害。
    LaunchedEffect(liveSampling) {
        if (!liveSampling) return@LaunchedEffect
        while (isActive) {
            val state = MonitorEngine.downgrade.value
            val phaseNow = state.phase(System.currentTimeMillis(), NetPilot.thresholds())
            val countingPhase =
                phaseNow == MonitorPhase.RECOVERING || phaseNow == MonitorPhase.ROLLBACK
            // 为什么两个条件都要有：`liveSampling` 只在重组发生时才会变（切标签页、退到后台
            // 都有帧，所以那条路有效），但关屏后没有帧，它变不了；`isScreenInteractive`
            // 每轮自己问一次系统，不依赖帧。两道门叠加起来，「页面不可见」与「屏幕关闭」
            // 都真的不再探测。
            // 注意这里是**跳过本轮**而不是 break：循环一旦退出，就再没有帧来把它拉起来，
            // 屏幕重新点亮后页面会永远停在旧数据上。
            if (!countingPhase && isScreenInteractive(context)) {
                runCatching { NetPilot.sampleNow(context) }
            }
            // 磁贴、通知、功能页都可能改这个开关，顺手同步一次，避免开关显示和实际不一致。
            autoEnabled = NetPilot.autoDowngradeEnabled()
            delay(FAST_SAMPLE_INTERVAL_MS)
        }
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // ---- 手动操作 ----
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
        // 非挂起调用：只清计数，不碰网络制式本身。
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

    // ---- 显示用的派生值 ----
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
                TopAppBar(
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
                // ---------- 1. 监控总开关 + 循环 / 状态机 ----------
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

                // ---------- 2. 实时信号 ----------
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
                                // 读不到 SINR 时不再只丢一个「未知」，把具体原因写出来：
                                // 是缺权限、定位没开，还是这个小区/固件根本不报这个字段。
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
                                // 只显示主机名：pingTarget 现在是完整 URL（含路径），
                                // 整串塞进标题会把这一行挤爆，用户只需要知道在探哪台。
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
                                    // 省电跳过的那一轮没有读数：说清楚是「没去探」，
                                    // 不要把省电显示成「网络不通」。
                                    stringResource(R.string.monitor_value_ping_skipped)
                                } else {
                                    noResponse
                                },
                            )
                            val pingError = snap.pingError
                            if (!pingError.isNullOrBlank()) {
                                // 异常串是「类名 + message」，很长而且以后可能更长。
                                // BasicComponent 不支持多行（0.9.4 没有 maxLines），
                                // 塞进 summary 只会被裁成半句，所以单独换行渲染。
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

                // ---------- 3. 网络质量判定 ----------
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
                        // Ping 全失败到底算不算差，取决于这个开关：把它和当前状态
                        // 摆在一起，用户才不用去「功能」页猜。
                        MonitorInfoRow(
                            title = stringResource(R.string.q_judge_ping_fail_title),
                            summary = stringResource(
                                R.string.q_judge_ping_fail_value,
                                if (thresholds.downgradeOnPingFail) boolOn else boolOff,
                            ),
                        )
                    }
                }

                // ---------- 4. 判定标准（当前生效值） ----------
                // 整块内容全部由 thresholds 拼出来，用户在「功能」页改一个数
                // （ConfigState 一变就触发重组），这里立刻跟着变，绝不写死。
                item {
                    MonitorSection(title = stringResource(R.string.q_section_criteria)) {
                        MonitorCriteriaLines(thresholds)
                    }
                }

                // ---------- 5. 阈值一览（只读） ----------
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

                // ---------- 6. 手动操作 ----------
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

                // ---------- 7. 特权通道 ----------
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
                            // 门面把各通道用「；」拼成一行，这里拆开逐条列，省得挤在一起看不清。
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

/** 一节内容：小标题 + 一张卡片，卡片里统一用 Column 排列若干行。 */
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

/** 只读信息行。[summary] 为 null 时只显示标题。 */
@Composable
private fun MonitorInfoRow(title: String, summary: String? = null) {
    BasicComponent(title = title, summary = summary)
}

/** 可点操作行；`enabled = false` 时 Miuix 会自己置灰并吞掉点击。 */
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

/** 状态机阶段 → 可读中文。 */
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
