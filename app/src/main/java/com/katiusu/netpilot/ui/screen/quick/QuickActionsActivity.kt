package com.katiusu.netpilot.ui.screen.quick

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.MainActivity
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.ui.component.SectionTitle
import com.katiusu.netpilot.ui.component.SettingsActionWithArrow
import com.katiusu.netpilot.ui.screen.datacard.DataCardActivity
import com.katiusu.netpilot.ui.screen.subpage.BaseSubPageActivity
import com.katiusu.netpilot.ui.util.LocalSubPageScrollBehavior
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 主页的「快速操作」二级页面。
 *
 * 为什么单独开一个 Activity 而不是弹窗：主页重写（1.6.0）后要求主页只保留「一眼看懂的
 * 状态 + 入口」，而这一页里有带副作用的按钮（切制式、锁 LTE、申请 Shizuku 权限）和一个
 * 会持续读状态的开关，塞进弹窗既滚动难，又会被主页的采样刷新带着重建。
 */
class QuickActionsActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.np_quick_title

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val scrollBehavior = LocalSubPageScrollBehavior.current

        var busy by remember { mutableStateOf(false) }
        var modeValue by remember { mutableStateOf(-1) }
        var channel by remember { mutableStateOf("") }
        var autoEnabled by remember { mutableStateOf(NetPilot.autoDowngradeEnabled()) }
        var message by remember { mutableStateOf<String?>(null) }

        fun refresh() {
            scope.launch {
                val subId = NetPilot.defaultDataSubId()
                modeValue = if (subId >= 0) NetPilot.currentModeValue(subId) else -1
                channel = NetPilot.channelSummary()
            }
        }

        LaunchedEffect(Unit) { refresh() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollBehavior != null) {
                        Modifier.pageScrollModifiers(
                            showTopAppBar = true,
                            topAppBarScrollBehavior = scrollBehavior,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentPadding = contentPadding,
        ) {
            item {
                SectionTitle(stringResource(R.string.np_quick_section_preset))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PresetButton(
                                textRes = R.string.np_home_preset_5g,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                runPreset(context, "5g", scope) { busy = it }
                            }
                            PresetButton(
                                textRes = R.string.np_home_preset_5g_only,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                runPreset(context, "5g_only", scope) { busy = it }
                            }
                            PresetButton(
                                textRes = R.string.np_home_preset_4g_only,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                runPreset(context, "4g_only", scope) { busy = it }
                            }
                        }
                        message?.let {
                            Text(
                                text = it,
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                                style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote2,
                                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }
                }
            }

            item { QuickManualSection(busy) { on -> busy = on } }

            item {
                SectionTitle(stringResource(R.string.np_quick_section_auto))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = stringResource(R.string.np_fake5g_enabled_title),
                        summary = if (autoEnabled) {
                            stringResource(R.string.np_home_auto_summary_on, com.katiusu.netpilot.core.monitor.MonitorSettings.thresholds().monitorIntervalSec)
                        } else {
                            stringResource(R.string.np_home_auto_summary_off)
                        },
                        checked = autoEnabled,
                        onCheckedChange = { on ->
                            NetPilot.setAutoDowngrade(context, on)
                            autoEnabled = NetPilot.autoDowngradeEnabled()
                        },
                    )
                }
            }

            item {
                SectionTitle(stringResource(R.string.np_quick_section_channel))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = channel.ifBlank { stringResource(R.string.np_home_channel_none) },
                            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                text = stringResource(R.string.np_home_refresh),
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val r = NetPilot.refreshChannel()
                                        channel = NetPilot.channelSummary()
                                        message = r
                                        busy = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = stringResource(R.string.np_home_shizuku),
                                enabled = !busy,
                                onClick = {
                                    val ok = NetPilot.requestShizukuPermission()
                                    message = context.getString(
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

            item {
                SectionTitle(stringResource(R.string.np_quick_section_nav))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column {
                        SettingsActionWithArrow(
                            title = stringResource(R.string.np_quick_nav_monitor),
                            summary = stringResource(R.string.np_quick_nav_monitor_summary),
                        ) { openMainPage(context, MainActivity.PAGE_MONITOR) }
                        SettingsActionWithArrow(
                            title = stringResource(R.string.np_quick_nav_log),
                            summary = stringResource(R.string.np_quick_nav_log_summary),
                        ) { openMainPage(context, MainActivity.PAGE_LOG) }
                        SettingsActionWithArrow(
                            title = stringResource(R.string.np_home_open_datacard),
                            summary = stringResource(R.string.np_home_action_datacard_summary),
                        ) { context.startActivity(Intent(context, DataCardActivity::class.java)) }
                    }
                }
            }
        }
    }

    /**
     * 立即检测与锁定 LTE 两件事都要求「先拿到默认数据卡」，
     * 所以放进独立的 section：按钮忙态与刷新态跟制式预设分开，避免切制式时整页卡住。
     */
    @Composable
    internal fun QuickManualSection(
        busy: Boolean,
        onBusyChange: (Boolean) -> Unit,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        SectionTitle(stringResource(R.string.np_quick_section_manual))
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.np_home_mode),
                    summary = stringResource(R.string.np_quick_manual_hint),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.np_home_sample),
                        enabled = !busy,
                        onClick = {
                            onBusyChange(true)
                            scope.launch {
                                runCatching { NetPilot.sampleNow(context) }
                                onBusyChange(false)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.np_home_lock_lte),
                        enabled = !busy,
                        onClick = {
                            onBusyChange(true)
                            scope.launch {
                                val ok = runCatching { NetPilot.lockLte(context, true) }.getOrDefault(false)
                                onBusyChange(false)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        if (ok) R.string.np_home_result_ok else R.string.np_home_result_fail
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    @Composable
    private fun PresetButton(
        textRes: Int,
        enabled: Boolean,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        TextButton(
            text = stringResource(textRes),
            enabled = enabled,
            onClick = onClick,
            modifier = modifier.height(44.dp),
        )
    }

    private fun runPreset(
        context: android.content.Context,
        preset: String,
        scope: kotlinx.coroutines.CoroutineScope,
        onBusy: (Boolean) -> Unit,
    ) {
        onBusy(true)
        scope.launch {
            val subId = NetPilot.defaultDataSubId()
            val ok = if (subId >= 0) {
                runCatching { NetPilot.applyPreset(subId, preset) }.getOrDefault(false)
            } else {
                false
            }
            onBusy(false)
            Toast.makeText(
                context,
                context.getString(
                    if (ok) R.string.np_home_result_ok else R.string.np_home_result_fail
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun openMainPage(context: android.content.Context, page: Int) {
        // CLEAR_TOP + SINGLE_TOP：把栈里的 MainActivity 复用回来并让它走 onNewIntent，
        // 而不是在快速操作页上面再压一个新的 MainActivity 实例 —— 否则「主页 → 快速操作 → 监控详情」
        // 之后要按两次返回才回得到主页，而且返回时看到的是那个新实例自己的页状态。
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
