package io.github.katiusu.netpilot.ui.screen.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.AppSettings
import io.github.katiusu.netpilot.LocaleHelper
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.ServicesGate
import io.github.katiusu.netpilot.core.datacard.SimReader
import io.github.katiusu.netpilot.core.keepalive.KeepAliveScheduler
import io.github.katiusu.netpilot.core.monitor.MonitorEngine
import io.github.katiusu.netpilot.core.monitor.MonitorSettings
import io.github.katiusu.netpilot.core.priv.SystemCompatInfo
import io.github.katiusu.netpilot.core.priv.WriteDiag
import io.github.katiusu.netpilot.core.priv.WriteVerification
import io.github.katiusu.netpilot.core.radio.BasebandDetector
import io.github.katiusu.netpilot.core.radio.BasebandProfile
import io.github.katiusu.netpilot.prefs.ConfigBackup
import io.github.katiusu.netpilot.prefs.ConfigState
import io.github.katiusu.netpilot.ui.component.AppArrowPreference
import io.github.katiusu.netpilot.ui.component.AppLazyPage
import io.github.katiusu.netpilot.ui.component.AppSectionCard
import io.github.katiusu.netpilot.ui.screen.about.AboutActivity
import io.github.katiusu.netpilot.ui.util.LocalPreferenceBackdrop
import io.github.katiusu.netpilot.ui.util.MiuixExpandSpec
import java.io.BufferedReader
import java.io.InputStreamReader
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                "${it.versionName ?: "1.0"} (${it.longVersionCode})"
            }
        } catch (_: Exception) { "1.0" }
    }

    var compatProbe by remember { mutableStateOf<SystemCompatInfo.Probe?>(null) }
    var compatProbing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        compatProbing = true
        compatProbe = runCatching { SystemCompatInfo.probe(context) }.getOrNull()
        compatProbing = false
    }

    // 基带一行：先显示缓存（上次成功探测的结论），再在后台重新探一次覆盖它。
    // 探测是纯只读属性读 + stat + getService，最坏 3 秒，放 LaunchedEffect 里不挡首帧；
    // 没有缓存时按「无结论」显示，绝不假装探测成功。
    var basebandLine by remember {
        mutableStateOf(BasebandDetector.cached()?.summaryLine() ?: BasebandProfile.UNKNOWN_SUMMARY)
    }
    LaunchedEffect(Unit) {
        basebandLine = runCatching { BasebandDetector.detect(context).summaryLine() }
            .getOrDefault(BasebandDetector.cached()?.summaryLine() ?: BasebandProfile.UNKNOWN_SUMMARY)
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

    var subsimAutoDetect by remember {
        mutableStateOf(
            ConfigState.bool(
                MonitorSettings.KEY_SUBSIM_AUTO_DETECT,
                MonitorSettings.DEFAULT_SUBSIM_AUTO_DETECT,
            )
        )
    }
    var subsimAutoLte by remember {
        mutableStateOf(
            ConfigState.bool(
                MonitorSettings.KEY_SUBSIM_AUTO_LTE,
                MonitorSettings.DEFAULT_SUBSIM_AUTO_LTE,
            )
        )
    }
    var manualSubId by remember {
        mutableIntStateOf(
            ConfigState.int(
                MonitorSettings.KEY_SUBSIM_MANUAL_SUB_ID,
                MonitorSettings.DEFAULT_SUBSIM_MANUAL_SUB_ID,
            )
        )
    }
    val sims = remember(subsimAutoDetect) { SimReader.sims(context) }

    var servicesEnabled by remember { mutableStateOf(ServicesGate.enabled(context)) }
    val monitorRunning by MonitorEngine.running.collectAsState()
    val heartbeatRegistered = remember(servicesEnabled, monitorRunning) {
        KeepAliveScheduler.isScheduled(context)
    }

    var verifyWrite by remember { mutableStateOf(WriteVerification.enabled(context)) }
    var verboseLog by remember { mutableStateOf(WriteDiag.enabled(context)) }
    val compat = remember { SystemCompatInfo.read(context) }
    val unknownText = stringResource(R.string.ka_compat_unknown)
    val probingText = stringResource(R.string.ka_compat_probing)
    val noChannelText = stringResource(R.string.ka_compat_channel_none)
    val readNoValueText = stringResource(R.string.ka_compat_read_no_value)
    val probed = compatProbe
    val writeMethodsText = probed?.writeMethods?.ifBlank { unknownText }
        ?: if (compatProbing) probingText else unknownText
    val carrierText = probed?.carrierInfo?.ifBlank { unknownText }
        ?: if (compatProbing) probingText else unknownText
    val selfCheckText = when {
        probed == null -> if (compatProbing) probingText else unknownText
        probed.selfCheck == null -> stringResource(R.string.ka_compat_self_check_skipped)
        probed.selfCheck.ok ->
            stringResource(R.string.ka_compat_self_check_ok, probed.selfCheck.note)
        else ->
            stringResource(R.string.ka_compat_self_check_failed, probed.selfCheck.note)
    }
    val modelText = compat.model.ifBlank { unknownText }
    val channelText = when {
        compatProbing -> probingText
        probed != null && probed.channelLabel.isNotBlank() -> probed.channelLabel
        probed != null -> noChannelText
        else -> unknownText
    }
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

    var autoUpdate by remember { mutableStateOf(AppSettings.load(context).checkUpdateOnLaunch) }

    AppLazyPage(
        title = stringResource(R.string.tab_settings),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
    ) {
        item {
            Column {
                AppSectionCard(
                    title = stringResource(R.string.settings_interface),
                ) {
                    val modes = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark),
                        stringResource(R.string.theme_monet_system),
                        stringResource(R.string.theme_monet_light),
                        stringResource(R.string.theme_monet_dark),
                    )
                    val modesEnum = listOf(
                        ColorSchemeMode.System,
                        ColorSchemeMode.Light,
                        ColorSchemeMode.Dark,
                        ColorSchemeMode.MonetSystem,
                        ColorSchemeMode.MonetLight,
                        ColorSchemeMode.MonetDark,
                    )
                    val currentIndex = modesEnum.indexOf(currentMode).takeIf { it >= 0 } ?: 0

                    GlassDropdownPreference(
                        title = stringResource(R.string.theme_mode),
                        items = modes,
                        selectedIndex = currentIndex,
                        onSelectedIndexChange = { onModeChange(modesEnum[it]) },
                        backdrop = LocalPreferenceBackdrop.current,
                        renderInRootScaffold = true,
                    )

                    SwitchPreference(
                        title = stringResource(R.string.floating_navbar),
                        summary = stringResource(R.string.floating_navbar_summary),
                        checked = isFloatingNavbar,
                        onCheckedChange = onFloatingNavbarChange,
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
                            onCheckedChange = onLiquidGlassChange,
                        )
                    }

                    SwitchPreference(
                        title = stringResource(R.string.blur_enabled),
                        summary = stringResource(R.string.blur_enabled_summary),
                        checked = isBlurEnabled,
                        onCheckedChange = onBlurEnabledChange,
                    )
                }

                AppSectionCard(
                    title = stringResource(R.string.settings_language),
                ) {
                    val languageNames = listOf(
                        stringResource(R.string.language_default),
                        stringResource(R.string.language_zh_cn),
                        stringResource(R.string.language_en),
                    )
                    val languageValues = listOf(
                        LocaleHelper.Language.SYSTEM,
                        LocaleHelper.Language.ZH_CN,
                        LocaleHelper.Language.EN,
                    )
                    val savedLanguage = LocaleHelper.getSavedLanguage(context)
                    val langCurrentIndex = languageValues.indexOf(savedLanguage).takeIf { it >= 0 } ?: 0

                    GlassDropdownPreference(
                        title = stringResource(R.string.settings_language),
                        items = languageNames,
                        selectedIndex = langCurrentIndex,
                        onSelectedIndexChange = {
                            LocaleHelper.setLanguage(context, languageValues[it])
                            activity?.recreate()
                        },
                        backdrop = LocalPreferenceBackdrop.current,
                        renderInRootScaffold = true,
                    )
                }

                AppSectionCard(
                    title = stringResource(R.string.settings_data),
                ) {
                    AppArrowPreference(
                        title = stringResource(R.string.export_settings),
                        summary = stringResource(R.string.export_settings_summary),
                        onClick = { exportLauncher.launch("NetPilot_settings.json") },
                    )
                    AppArrowPreference(
                        title = stringResource(R.string.import_settings),
                        summary = stringResource(R.string.import_settings_summary),
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "text/plain",
                                    "application/octet-stream",
                                    "*/*",
                                )
                            )
                        },
                    )
                }

                AppSectionCard(
                    title = stringResource(R.string.np_section_subsim),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.np_subsim_auto_detect_title),
                        summary = stringResource(
                            if (subsimAutoDetect) R.string.np_subsim_auto_detect_on
                            else R.string.np_subsim_auto_detect_off
                        ),
                        checked = subsimAutoDetect,
                        onCheckedChange = { on ->
                            subsimAutoDetect = on
                            ConfigState.set(MonitorSettings.KEY_SUBSIM_AUTO_DETECT, on)
                        },
                    )
                    if (!subsimAutoDetect) {
                        MiuixText(
                            text = stringResource(R.string.np_subsim_manual_title),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                        )
                        if (sims.isEmpty()) {
                            MiuixText(
                                text = stringResource(R.string.np_subsim_manual_none),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote2,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp, vertical = 2.dp),
                            ) {
                                sims.forEach { sim ->
                                    TextButton(
                                        text = stringResource(
                                            R.string.np_subsim_manual_entry,
                                            sim.slotIndex + 1,
                                            sim.title,
                                        ),
                                        onClick = {
                                            manualSubId = sim.subId
                                            ConfigState.set(
                                                MonitorSettings.KEY_SUBSIM_MANUAL_SUB_ID,
                                                sim.subId,
                                            )
                                        },
                                        colors = if (manualSubId == sim.subId) {
                                            ButtonDefaults.textButtonColorsPrimary()
                                        } else {
                                            ButtonDefaults.textButtonColors()
                                        },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                        }
                    }
                    SwitchPreference(
                        title = stringResource(R.string.np_subsim_auto_lte_title),
                        summary = stringResource(
                            if (subsimAutoLte) R.string.np_subsim_auto_lte_on
                            else R.string.np_subsim_auto_lte_off
                        ),
                        checked = subsimAutoLte,
                        onCheckedChange = { on ->
                            subsimAutoLte = on
                            ConfigState.set(MonitorSettings.KEY_SUBSIM_AUTO_LTE, on)
                        },
                    )
                    Footnote(stringResource(R.string.np_subsim_hint))
                }

                AppSectionCard(
                    title = stringResource(R.string.ka_section_services),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.ka_gate_title),
                        summary = stringResource(R.string.ka_gate_summary),
                        checked = servicesEnabled,
                        onCheckedChange = { on ->
                            servicesEnabled = on
                            NetPilot.setServicesEnabled(context, on)
                        },
                    )
                    Footnote(stringResource(R.string.ka_gate_hint))
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
                            ),
                        ),
                    )
                    Footnote(stringResource(R.string.ka_status_hint))
                }

                AppSectionCard(
                    title = stringResource(R.string.ka_section_compat),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.ka_verify_title),
                        summary = stringResource(R.string.ka_verify_summary),
                        checked = verifyWrite,
                        onCheckedChange = { on ->
                            verifyWrite = on
                            WriteVerification.setEnabled(context, on)
                        },
                    )
                    Footnote(stringResource(R.string.ka_verify_hint))
                    SwitchPreference(
                        title = stringResource(R.string.ka_verbose_title),
                        summary = stringResource(R.string.ka_verbose_summary),
                        checked = verboseLog,
                        onCheckedChange = { on ->
                            verboseLog = on
                            WriteDiag.setEnabled(context, on)
                        },
                    )
                    Footnote(stringResource(R.string.ka_verbose_hint))
                    CompatInfoRow(
                        title = stringResource(R.string.ka_compat_vendor),
                        summary = compat.vendor.ifBlank { unknownText },
                    )
                    // 基带一行：文案在 core.radio 里拼好（含「无结论」的降级说法），
                    // 这里只显示。标题用字面量是因为 strings.xml 不在本次允许改动的范围内。
                    CompatInfoRow(
                        title = "基带",
                        summary = basebandLine,
                    )
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
                    if (probed != null && probed.writeCommand.isNotBlank()) {
                        Footnote(
                            stringResource(R.string.ka_compat_write_command, probed.writeCommand)
                        )
                    }
                    if (probed != null && probed.channelReason.isNotBlank()) {
                        Footnote(
                            stringResource(R.string.ka_compat_channel_reason, probed.channelReason)
                        )
                    }
                    Footnote(stringResource(R.string.ka_compat_unknown_note))
                    Footnote(stringResource(R.string.ka_compat_conclusion))
                }

                AppSectionCard(
                    title = stringResource(R.string.settings_update),
                ) {
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
                        },
                    )
                }

                AppSectionCard(
                    title = stringResource(R.string.about),
                ) {
                    AppArrowPreference(
                        title = stringResource(R.string.about),
                        summary = appVersion,
                        onClick = {
                            context.startActivity(Intent(context, AboutActivity::class.java))
                        },
                    )
                }

                MiuixText(
                    text = stringResource(R.string.settings_footer, appVersion),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    MiuixText(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote2,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
    )
}

@Composable
private fun CompatInfoRow(title: String, summary: String? = null) {
    BasicComponent(title = title, summary = summary)
}

