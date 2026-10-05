package com.katiusu.netpilot.ui.screen.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.AppSettings
import com.katiusu.netpilot.LocaleHelper
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.ServicesGate
import com.katiusu.netpilot.core.keepalive.KeepAliveScheduler
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.priv.SystemCompatInfo
import com.katiusu.netpilot.core.priv.WriteDiag
import com.katiusu.netpilot.core.priv.WriteVerification
import com.katiusu.netpilot.ui.screen.about.AboutActivity
import com.katiusu.netpilot.prefs.ConfigBackup
import com.katiusu.netpilot.ui.util.BlurredBar
import com.katiusu.netpilot.ui.util.MiuixExpandSpec
import com.katiusu.netpilot.ui.util.blurSource
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import com.katiusu.netpilot.ui.util.rememberBlurState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun SettingsPageView(
    currentMode: ColorSchemeMode,
    onModeChange: (ColorSchemeMode) -> Unit,
    isFloatingNavbar: Boolean,
    onFloatingNavbarChange: (Boolean) -> Unit,
    isLiquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    isBlurEnabled: Boolean,
    onBlurEnabledChange: (Boolean) -> Unit,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val title = stringResource(R.string.tab_settings)

    val hazeState = rememberBlurState()
    val blurActive = isBlurEnabled && hazeState != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val appVersion = remember {
        try {
            // 与「关于」页保持一致的「语义版本 (构建号)」格式：报问题时构建号才有意义。
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                "${it.versionName ?: "1.0"} (${it.longVersionCode})"
            }
        } catch (_: Exception) { "1.0" }
    }

    // 「当前通道 / 写入方式 / 读取探测」这三项都必须真跑一遍才能得到，而探测要起 su 或
    // app_process（最长十几秒）。所以：
    // - 放在页面级、不放进 LazyColumn 的 item 里 —— 否则滚出屏幕会重新组合 → 反复起 shell；
    // - 交给 SystemCompatInfo.probe()，它内部走 Dispatchers.IO：UI 线程只负责显示「检测中…」，
    //   探测无论如何失败都只会让这几行显示「未知」，不会影响页面其它部分。
    var compatProbe by remember { mutableStateOf<SystemCompatInfo.Probe?>(null) }
    var compatProbing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        compatProbing = true
        compatProbe = runCatching { SystemCompatInfo.probe(context) }.getOrNull()
        compatProbing = false
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                val json = ConfigBackup.exportJson(context)
                context.contentResolver.openOutputStream(it)?.use { output ->
                    output.write(json.toByteArray())
                }
                Toast.makeText(context, resources.getString(R.string.export_success), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, resources.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val json = reader.readText()
                reader.close()
                inputStream?.close()
                if (ConfigBackup.importJson(context, json)) {
                    Toast.makeText(context, resources.getString(R.string.import_success), Toast.LENGTH_SHORT).show()
                    activity?.recreate()
                } else {
                    Toast.makeText(context, resources.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, resources.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(hazeState, blurActive, scrollBehavior) {
                TopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .blurSource(if (isBlurEnabled) hazeState else null)
                    .pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                    ),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + extraBottomPadding
                )
            ) {
                item {
                    Column {
                        SmallTitle(text = stringResource(R.string.settings_interface))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                val modes = listOf(
                                    stringResource(R.string.theme_system),
                                    stringResource(R.string.theme_light),
                                    stringResource(R.string.theme_dark),
                                    stringResource(R.string.theme_monet_system),
                                    stringResource(R.string.theme_monet_light),
                                    stringResource(R.string.theme_monet_dark)
                                )
                                val modesEnum = listOf(
                                    ColorSchemeMode.System,
                                    ColorSchemeMode.Light,
                                    ColorSchemeMode.Dark,
                                    ColorSchemeMode.MonetSystem,
                                    ColorSchemeMode.MonetLight,
                                    ColorSchemeMode.MonetDark
                                )
                                val currentIndex = modesEnum.indexOf(currentMode).takeIf { it >= 0 } ?: 0

                                WindowDropdownPreference(
                                    title = stringResource(R.string.theme_mode),
                                    summary = modes[currentIndex],
                                    items = modes,
                                    selectedIndex = currentIndex,
                                    onSelectedIndexChange = { onModeChange(modesEnum[it]) },
                                    onExpandedChange = { }
                                )

                                SwitchPreference(
                                    title = stringResource(R.string.floating_navbar),
                                    summary = stringResource(R.string.floating_navbar_summary),
                                    checked = isFloatingNavbar,
                                    onCheckedChange = onFloatingNavbarChange
                                )

                                AnimatedVisibility(
                                    visible = isFloatingNavbar,
                                    enter = expandVertically(animationSpec = MiuixExpandSpec),
                                    exit = shrinkVertically(animationSpec = MiuixExpandSpec),
                                ) {
                                    SwitchPreference(
                                        title = stringResource(R.string.liquid_glass),
                                        summary = stringResource(R.string.liquid_glass_summary),
                                        checked = isLiquidGlass,
                                        onCheckedChange = onLiquidGlassChange
                                    )
                                }

                                SwitchPreference(
                                    title = stringResource(R.string.blur_enabled),
                                    summary = stringResource(R.string.blur_enabled_summary),
                                    checked = isBlurEnabled,
                                    onCheckedChange = onBlurEnabledChange
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.settings_language))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            val languageNames = listOf(
                                stringResource(R.string.language_default),
                                stringResource(R.string.language_zh_cn),
                                stringResource(R.string.language_en)
                            )
                            val languageValues = listOf(
                                LocaleHelper.Language.SYSTEM,
                                LocaleHelper.Language.ZH_CN,
                                LocaleHelper.Language.EN
                            )
                            val savedLanguage = LocaleHelper.getSavedLanguage(context)
                            val langCurrentIndex = languageValues.indexOf(savedLanguage)
                                .takeIf { it >= 0 } ?: 0

                            WindowDropdownPreference(
                                title = stringResource(R.string.settings_language),
                                summary = languageNames[langCurrentIndex],
                                items = languageNames,
                                selectedIndex = langCurrentIndex,
                                onSelectedIndexChange = {
                                    LocaleHelper.setLanguage(context, languageValues[it])
                                    activity?.recreate()
                                },
                                onExpandedChange = { }
                            )
                        }

                        SmallTitle(text = stringResource(R.string.settings_data))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                ArrowPreference(
                                    title = stringResource(R.string.export_settings),
                                    summary = stringResource(R.string.export_settings_summary),
                                    onClick = {
                                        exportLauncher.launch("NetPilot_settings.json")
                                    }
                                )

                                ArrowPreference(
                                    title = stringResource(R.string.import_settings),
                                    summary = stringResource(R.string.import_settings_summary),
                                    onClick = {
                                        // 用 OpenDocument + 宽 MIME 匹配：很多文件管理器把 .json
                                        // 报成 application/octet-stream，GetContent("application/json")
                                        // 会让文件在系统选择器里灰掉、根本点不动。
                                        importLauncher.launch(
                                            arrayOf(
                                                "application/json",
                                                "text/plain",
                                                "application/octet-stream",
                                                "*/*",
                                            )
                                        )
                                    }
                                )
                            }
                        }

                        // ---------- 后台服务（总开关） ----------
                        // 开关状态用本地 mutableState 而不是每次读 ConfigState：
                        // 本地状态保证点击后一定重组（ConfigState 也是 snapshot 状态，
                        // 但显式持有更不依赖 Miuix 内部的重组时机）。
                        var servicesEnabled by remember { mutableStateOf(ServicesGate.enabled(context)) }
                        val monitorRunning by MonitorEngine.running.collectAsState()
                        // 心跳是否已注册：用开关与运行状态做 key 重算，
                        // 不能放无参 remember —— 那样点了开关状态行永远不刷新。
                        val heartbeatRegistered = remember(servicesEnabled, monitorRunning) {
                            KeepAliveScheduler.isScheduled(context)
                        }
                        SmallTitle(text = stringResource(R.string.ka_section_services))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                SwitchPreference(
                                    title = stringResource(R.string.ka_gate_title),
                                    summary = stringResource(R.string.ka_gate_summary),
                                    checked = servicesEnabled,
                                    onCheckedChange = { on ->
                                        servicesEnabled = on
                                        NetPilot.setServicesEnabled(context, on)
                                    }
                                )
                                MiuixText(
                                    text = stringResource(R.string.ka_gate_hint),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_status_title),
                                    summary = stringResource(
                                        R.string.ka_status_summary,
                                        stringResource(
                                            if (monitorRunning) R.string.ka_status_running
                                            else R.string.ka_status_stopped
                                        ),
                                        stringResource(
                                            if (heartbeatRegistered) R.string.ka_heartbeat_on
                                            else R.string.ka_heartbeat_off
                                        )
                                    )
                                )
                                MiuixText(
                                    text = stringResource(R.string.ka_status_hint),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                                )
                            }
                        }

                        // ---------- 系统兼容性 ----------
                        var verifyWrite by remember { mutableStateOf(WriteVerification.enabled(context)) }
                        // 详细诊断日志（默认关）：把 core/priv 写入链路的每一步都记进日志页。
                        // 与上面的回读校验相邻，因为两者回答的是同一类问题：写下去到底有没有生效。
                        var verboseLog by remember { mutableStateOf(WriteDiag.enabled(context)) }
                        // 机型信息一整个生命周期都不变，remember 一次即可（读取要反射，别每帧做）。
                        val compat = remember { SystemCompatInfo.read(context) }
                        val unknownText = stringResource(R.string.ka_compat_unknown)
                        val probingText = stringResource(R.string.ka_compat_probing)
                        val noChannelText = stringResource(R.string.ka_compat_channel_none)
                        val readNoValueText = stringResource(R.string.ka_compat_read_no_value)
                        val probed = compatProbe
                        // 1.5.0：真探测出来的值，null 时按现有约定显示「检测中…」/「未知」。
                        val writeMethodsText = probed?.writeMethods?.ifBlank { unknownText }
                            ?: if (compatProbing) probingText else unknownText
                        // 运营商识别（1.5.0）：由当前默认数据卡的 simOperator（MCC+MNC）反查，
                        // 识别不出时写「不在内置运营商表中」而不是「未知」—— 那句话本身就是排查线索，
                        // 它说明表里缺这张卡，而不是这台机器读不到卡。
                        val carrierText = probed?.carrierInfo?.ifBlank { unknownText }
                            ?: if (compatProbing) probingText else unknownText
                        // 程序自检（1.5.1 补丁）：取代原先那条「应用进程直接读权威存储」的检查。
                        // 那条路必然被 provider 的 uid 名单挡住，结论永远是「被拒绝」；
                        // 现在改成让特权通道自己跑一次探针，回答「这个程序能不能跑起来」。
                        val selfCheckText = when {
                            probed == null -> if (compatProbing) probingText else unknownText
                            probed.selfCheck == null -> stringResource(R.string.ka_compat_self_check_skipped)
                            probed.selfCheck.ok ->
                                stringResource(R.string.ka_compat_self_check_ok, probed.selfCheck.note)
                            else ->
                                stringResource(R.string.ka_compat_self_check_failed, probed.selfCheck.note)
                        }
                        // 直接显示系统报出来的型号，不做拼接、不补市场名：用户要的就是
                        // 「这台机器是什么」，加括号补一个名字反而像两行信息挤在一格里。
                        val modelText = compat.model.ifBlank { unknownText }
                        // 下面三行每个值都来自刚才那次真实探测；探测没完成显示「检测中…」，
                        // 探测完成但这一项为空则显示「未知」/「未检测到」，绝不留任何固定展示值。
                        val channelText = when {
                            compatProbing -> probingText
                            probed != null && probed.channelLabel.isNotBlank() -> probed.channelLabel
                            probed != null -> noChannelText
                            else -> unknownText
                        }
                        // 写入方式：由「真实通道 + 真实写入目标」推导，推导不出来就是「未知」。
                        val writeText = if (probed == null) {
                            if (compatProbing) probingText else unknownText
                        } else {
                            when (probed.stage) {
                                SystemCompatInfo.WriteStage.APP_PROCESS_CLI ->
                                    stringResource(R.string.ka_compat_write_cli, probed.targetSubId)
                                SystemCompatInfo.WriteStage.SETTINGS_FALLBACK ->
                                    stringResource(R.string.ka_compat_write_settings, probed.writeKey)
                                SystemCompatInfo.WriteStage.SHIZUKU_BINDER ->
                                    stringResource(R.string.ka_compat_write_shizuku, probed.targetSubId)
                                SystemCompatInfo.WriteStage.NONE -> unknownText
                            }
                        }
                        // 读取探测：真读过一次，成功和失败都如实显示；「未执行」和「失败」分开。
                        val readText = if (probed == null) {
                            if (compatProbing) probingText else unknownText
                        } else {
                            when (probed.readState) {
                                SystemCompatInfo.ReadState.OK ->
                                    stringResource(R.string.ka_compat_read_ok, probed.readRaw)
                                SystemCompatInfo.ReadState.FAILED ->
                                    stringResource(
                                        R.string.ka_compat_read_failed,
                                        probed.readNote.ifBlank { readNoValueText },
                                    )
                                SystemCompatInfo.ReadState.SKIPPED ->
                                    stringResource(R.string.ka_compat_read_skipped)
                                SystemCompatInfo.ReadState.NO_TARGET ->
                                    stringResource(R.string.ka_compat_read_no_target)
                            }
                        }
                        SmallTitle(text = stringResource(R.string.ka_section_compat))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                SwitchPreference(
                                    title = stringResource(R.string.ka_verify_title),
                                    summary = stringResource(R.string.ka_verify_summary),
                                    checked = verifyWrite,
                                    onCheckedChange = { on ->
                                        verifyWrite = on
                                        WriteVerification.setEnabled(context, on)
                                    }
                                )
                                MiuixText(
                                    text = stringResource(R.string.ka_verify_hint),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.ka_verbose_title),
                                    summary = stringResource(R.string.ka_verbose_summary),
                                    checked = verboseLog,
                                    onCheckedChange = { on ->
                                        verboseLog = on
                                        WriteDiag.setEnabled(context, on)
                                    }
                                )
                                MiuixText(
                                    text = stringResource(R.string.ka_verbose_hint),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_vendor),
                                    summary = compat.vendor.ifBlank { unknownText },
                                )
                                // 品牌与厂商是两件事（同一厂商可能挂多个牌子），所以分开两行。
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_brand),
                                    summary = compat.brand.ifBlank { unknownText },
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_model),
                                    summary = modelText,
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_android),
                                    summary = compat.android.ifBlank { unknownText },
                                )
                                // 标题资源名仍叫 ka_compat_miui（历史名），文案是「系统构建版本」：
                                // 值只取 ro.build.version.incremental，不再猜厂商 OS 名（见 getSystemBuild）。
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_miui),
                                    summary = compat.systemBuild.ifBlank { unknownText },
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_channel),
                                    summary = channelText,
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_carrier),
                                    summary = carrierText,
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_write),
                                    summary = writeText,
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_read),
                                    summary = readText,
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_write_methods),
                                    summary = writeMethodsText,
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_self_check),
                                    summary = selfCheckText,
                                )
                                CompatInfoRow(
                                    title = stringResource(R.string.ka_compat_verify_state),
                                    summary = stringResource(
                                        if (verifyWrite) R.string.ka_compat_verify_on
                                        else R.string.ka_compat_verify_off
                                    ),
                                )
                                // 完整命令是长串，放脚注（summary 有被截断的风险）；只有真推导出来才显示。
                                if (probed != null && probed.writeCommand.isNotBlank()) {
                                    MiuixText(
                                        text = stringResource(
                                            R.string.ka_compat_write_command,
                                            probed.writeCommand,
                                        ),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        style = MiuixTheme.textStyles.footnote2,
                                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                                    )
                                }
                                // 没有通道时把两条通道各自的失败原因摊开，用户才知道该去授权哪一个。
                                if (probed != null && probed.channelReason.isNotBlank()) {
                                    MiuixText(
                                        text = stringResource(
                                            R.string.ka_compat_channel_reason,
                                            probed.channelReason,
                                        ),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        style = MiuixTheme.textStyles.footnote2,
                                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                                    )
                                }
                                MiuixText(
                                    text = stringResource(R.string.ka_compat_unknown_note),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                                )
                                MiuixText(
                                    text = stringResource(R.string.ka_compat_conclusion),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                                )
                            }
                        }

                        // ---------- 更新（自动检查） ----------
                        // 为什么默认开：检查只在应用被打开时跑一次（MainActivity 的 LaunchedEffect），
                        // 完全不起后台轮询，所以打开它不会给这一轮「削后台唤醒」的目标加项。
                        // 回写用 copy：整对象覆盖会把主题、语言等字段一起冲成默认值。
                        var autoUpdate by remember { mutableStateOf(AppSettings.load(context).checkUpdateOnLaunch) }
                        SmallTitle(text = stringResource(R.string.settings_update))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                SwitchPreference(
                                    title = stringResource(R.string.check_update_on_launch),
                                    summary = stringResource(R.string.check_update_on_launch_summary),
                                    checked = autoUpdate,
                                    onCheckedChange = { on ->
                                        autoUpdate = on
                                        AppSettings.save(
                                            context,
                                            AppSettings.load(context).copy(checkUpdateOnLaunch = on)
                                        )
                                    }
                                )
                            }
                        }

                        SmallTitle(text = stringResource(R.string.about))
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                        ) {
                            Column {
                                ArrowPreference(
                                    title = stringResource(R.string.about),
                                    summary = appVersion,
                                    onClick = {
                                        context.startActivity(
                                            Intent(context, AboutActivity::class.java)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 只读信息行。用监控页 MonitorInfoRow 的同一个 Miuix 组件（`BasicComponent`），
 * 让「设置」与「监控」两页的信息行观感一致；Miuix 0.9.4 里它是无状态的，没有额外坑。
 */
@Composable
private fun CompatInfoRow(title: String, summary: String? = null) {
    BasicComponent(title = title, summary = summary)
}
