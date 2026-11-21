package io.github.katiusu.netpilot.ui.screen.features

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.mode.NetworkMode
import io.github.katiusu.netpilot.core.monitor.CarrierInfo
import io.github.katiusu.netpilot.core.monitor.MonitorSettings
import io.github.katiusu.netpilot.core.tasker.TaskerGate
import io.github.katiusu.netpilot.prefs.OptionSpec
import io.github.katiusu.netpilot.prefs.OptionType
import io.github.katiusu.netpilot.ui.component.pref.HookOptionsPage
import io.github.katiusu.netpilot.ui.component.pref.HookSection
import io.github.katiusu.netpilot.ui.screen.about.AboutActivity
import io.github.katiusu.netpilot.ui.screen.datacard.DataCardActivity
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun FeaturesPageView(
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val specs = remember { featureSpecs() }
    val sections = remember(specs) { featureSections(specs) }
    var showModePicker by remember { mutableStateOf(false) }

    HookOptionsPage(
        title = stringResource(R.string.tab_features),
        sections = sections,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onArrowClick = { spec ->
            when (spec.key) {
                KEY_DATACARD_MANAGE ->
                    context.startActivity(Intent(context, DataCardActivity::class.java))
                KEY_ABOUT ->
                    context.startActivity(Intent(context, AboutActivity::class.java))
                KEY_MODE_SWITCH -> showModePicker = true
            }
        },
    )

    if (showModePicker) {
        ModePickerDialog(onDismiss = { showModePicker = false })
    }
}

private const val KEY_DATACARD_MANAGE = "np_datacard_manage"
private const val KEY_ABOUT = "np_about"

private const val KEY_MODE_SWITCH = "np_mode_switch"

private fun specByKey(specs: List<OptionSpec>, key: String): OptionSpec =
    specs.first { it.key == key }

private fun specsByKeys(specs: List<OptionSpec>, vararg keys: String): List<OptionSpec> =
    keys.map { specByKey(specs, it) }

private fun featureSections(specs: List<OptionSpec>): List<HookSection> = listOf(
    HookSection(
        titleRes = R.string.np_section_fake5g,
        specs = specsByKeys(
            specs,
            MonitorSettings.KEY_ENABLED,
            MonitorSettings.KEY_RSRP,
            MonitorSettings.KEY_SINR,
            MonitorSettings.KEY_PING,
            MonitorSettings.KEY_PING_FAIL,
            MonitorSettings.KEY_NR_ONLY,
            MonitorSettings.KEY_WEAK_SIGNAL,
            MonitorSettings.KEY_WEAK_RSRP,
            MonitorSettings.KEY_TOGGLE_ENDC,
        ),
    ),
    HookSection(
        titleRes = R.string.np_section_policy,
        specs = specsByKeys(
            specs,
            KEY_MODE_SWITCH,
            MonitorSettings.KEY_DOWNGRADE_MODE,
            MonitorSettings.KEY_LOCK_LTE_MODE,
            MonitorSettings.KEY_COOLDOWN,
            MonitorSettings.KEY_RECOVERY,
            MonitorSettings.KEY_NO_NET_ROLLBACK,
        ),
    ),
    HookSection(
        titleRes = R.string.np_section_monitor,
        specs = specsByKeys(
            specs,
            MonitorSettings.KEY_INTERVAL,
            MonitorSettings.KEY_ADAPTIVE_INTERVAL,
            MonitorSettings.KEY_ADAPTIVE_MARGIN,
            MonitorSettings.KEY_ADAPTIVE_STEP,
            MonitorSettings.KEY_AUTO_START,
        ),
    ),
    HookSection(
        titleRes = R.string.np_section_datacard,
        specs = listOf(specByKey(specs, KEY_DATACARD_MANAGE)),
    ),
    HookSection(
        titleRes = R.string.np_section_tasker,
        specs = listOf(specByKey(specs, TaskerGate.KEY_ENABLED)),
    ),
    HookSection(
        titleRes = R.string.np_section_other,
        specs = listOf(specByKey(specs, KEY_ABOUT)),
    ),
)

internal fun featureSpecs(): List<OptionSpec> = listOf(
    OptionSpec(
        key = MonitorSettings.KEY_ENABLED,
        type = OptionType.SWITCH,
        titleRes = R.string.np_fake5g_enabled_title,
        summaryRes = R.string.np_fake5g_enabled_summary,
        defaultBoolean = MonitorSettings.DEFAULT_ENABLED,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_RSRP,
        type = OptionType.SLIDER,
        titleRes = R.string.np_fake5g_rsrp_title,
        summaryRes = R.string.np_fake5g_rsrp_summary,
        defaultFloat = MonitorSettings.DEFAULT_RSRP.toFloat(),
        dependsOn = MonitorSettings.KEY_ENABLED,
        sliderMin = -140f,
        sliderMax = -40f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_dbm,
        sliderValueLabelRes = R.string.np_fake5g_rsrp_title,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_SINR,
        type = OptionType.SLIDER,
        titleRes = R.string.np_fake5g_sinr_title,
        summaryRes = R.string.np_fake5g_sinr_summary,
        defaultFloat = MonitorSettings.DEFAULT_SINR.toFloat(),
        dependsOn = MonitorSettings.KEY_ENABLED,
        sliderMin = -20f,
        sliderMax = 30f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_db,
        sliderValueLabelRes = R.string.np_fake5g_sinr_title,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_PING,
        type = OptionType.SLIDER,
        titleRes = R.string.np_fake5g_ping_title,
        summaryRes = R.string.np_fake5g_ping_summary,
        defaultFloat = MonitorSettings.DEFAULT_PING.toFloat(),
        dependsOn = MonitorSettings.KEY_ENABLED,
        sliderMin = 50f,
        sliderMax = 2000f,
        sliderStep = 10f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_ms,
        sliderValueLabelRes = R.string.np_fake5g_ping_title,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_PING_FAIL,
        type = OptionType.SWITCH,
        titleRes = R.string.np_fake5g_ping_fail_title,
        summaryRes = R.string.np_fake5g_ping_fail_summary,
        defaultBoolean = false,
        dependsOn = MonitorSettings.KEY_ENABLED,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_NR_ONLY,
        type = OptionType.SWITCH,
        titleRes = R.string.np_fake5g_nr_only_title,
        summaryRes = R.string.np_fake5g_nr_only_summary,
        defaultBoolean = MonitorSettings.DEFAULT_FAKE5G_NR_ONLY,
        dependsOn = MonitorSettings.KEY_ENABLED,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_WEAK_SIGNAL,
        type = OptionType.SWITCH,
        titleRes = R.string.np_fake5g_weak_signal_title,
        summaryRes = R.string.np_fake5g_weak_signal_summary,
        defaultBoolean = MonitorSettings.DEFAULT_WEAK_SIGNAL,
        dependsOn = MonitorSettings.KEY_ENABLED,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_WEAK_RSRP,
        type = OptionType.SLIDER,
        titleRes = R.string.np_fake5g_weak_rsrp_title,
        summaryRes = R.string.np_fake5g_weak_rsrp_summary,
        defaultFloat = MonitorSettings.DEFAULT_WEAK_RSRP.toFloat(),
        dependsOn = MonitorSettings.KEY_WEAK_SIGNAL,
        sliderMin = -140f,
        sliderMax = -60f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_dbm,
        sliderValueLabelRes = R.string.np_fake5g_weak_rsrp_title,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_TOGGLE_ENDC,
        type = OptionType.SWITCH,
        titleRes = R.string.np_fake5g_endc_title,
        summaryRes = R.string.np_fake5g_endc_summary,
        defaultBoolean = false,
        dependsOn = MonitorSettings.KEY_ENABLED,
    ),
    OptionSpec(
        key = KEY_MODE_SWITCH,
        type = OptionType.ARROW,
        titleRes = R.string.np_mode_switch_title,
        summaryRes = R.string.np_mode_switch_summary,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_DOWNGRADE_MODE,
        type = OptionType.DROPDOWN,
        titleRes = R.string.np_fake5g_downgrade_mode_title,
        summaryRes = R.string.np_fake5g_downgrade_mode_summary,
        defaultString = MonitorSettings.DEFAULT_DOWNGRADE_MODE.toString(),
        dependsOn = MonitorSettings.KEY_ENABLED,
        entryResIds = listOf(
            R.string.np_mode_9,
            R.string.np_mode_11,
            R.string.np_mode_12,
            R.string.np_mode_26,
        ),
        entryValues = listOf("9", "11", "12", "26"),
    ),
    OptionSpec(
        key = MonitorSettings.KEY_LOCK_LTE_MODE,
        type = OptionType.DROPDOWN,
        titleRes = R.string.np_fake5g_lock_lte_mode_title,
        summaryRes = R.string.np_fake5g_lock_lte_mode_summary,
        defaultString = MonitorSettings.DEFAULT_LOCK_LTE_MODE.toString(),
        entryResIds = listOf(
            R.string.np_mode_follow_carrier,
            R.string.np_mode_9,
            R.string.np_mode_11,
            R.string.np_mode_12,
            R.string.np_mode_26,
            R.string.np_mode_27,
        ),
        entryValues = listOf("0", "9", "11", "12", "26", "27"),
    ),
    OptionSpec(
        key = MonitorSettings.KEY_COOLDOWN,
        type = OptionType.SLIDER,
        titleRes = R.string.np_fake5g_cooldown_title,
        summaryRes = R.string.np_fake5g_cooldown_summary,
        defaultFloat = MonitorSettings.DEFAULT_COOLDOWN.toFloat(),
        sliderMin = 30f,
        sliderMax = 7200f,
        sliderStep = 30f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_sec,
        sliderValueLabelRes = R.string.np_fake5g_cooldown_title,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_RECOVERY,
        type = OptionType.SLIDER,
        titleRes = R.string.np_fake5g_recovery_title,
        summaryRes = R.string.np_fake5g_recovery_summary,
        defaultFloat = MonitorSettings.DEFAULT_RECOVERY.toFloat(),
        sliderMin = 1f,
        sliderMax = 12f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_rounds,
        sliderValueLabelRes = R.string.np_fake5g_recovery_title,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_NO_NET_ROLLBACK,
        type = OptionType.SLIDER,
        titleRes = R.string.np_fake5g_no_net_rollback_title,
        summaryRes = R.string.np_fake5g_no_net_rollback_summary,
        defaultFloat = MonitorSettings.DEFAULT_NO_NET_ROLLBACK.toFloat(),
        sliderMin = 1f,
        sliderMax = 12f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_rounds,
        sliderValueLabelRes = R.string.np_fake5g_no_net_rollback_title,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_INTERVAL,
        type = OptionType.SLIDER,
        titleRes = R.string.np_fake5g_interval_title,
        summaryRes = R.string.np_fake5g_interval_summary,
        defaultFloat = MonitorSettings.DEFAULT_INTERVAL.toFloat(),
        sliderMin = 15f,
        sliderMax = 1800f,
        sliderStep = 15f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_sec,
        sliderValueLabelRes = R.string.np_fake5g_interval_title,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_ADAPTIVE_INTERVAL,
        type = OptionType.SWITCH,
        titleRes = R.string.np_adaptive_interval_title,
        summaryRes = R.string.np_adaptive_interval_summary,
        defaultBoolean = MonitorSettings.DEFAULT_ADAPTIVE_INTERVAL,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_ADAPTIVE_MARGIN,
        type = OptionType.SLIDER,
        titleRes = R.string.np_adaptive_margin_title,
        summaryRes = R.string.np_adaptive_margin_summary,
        defaultFloat = MonitorSettings.DEFAULT_ADAPTIVE_MARGIN.toFloat(),
        sliderMin = 2f,
        sliderMax = 30f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_dbm,
        sliderValueLabelRes = R.string.np_adaptive_margin_title,
        dependsOn = MonitorSettings.KEY_ADAPTIVE_INTERVAL,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_ADAPTIVE_STEP,
        type = OptionType.SLIDER,
        titleRes = R.string.np_adaptive_step_title,
        summaryRes = R.string.np_adaptive_step_summary,
        defaultFloat = MonitorSettings.DEFAULT_ADAPTIVE_STEP,
        sliderMin = MonitorSettings.ADAPTIVE_STEP_MIN,
        sliderMax = MonitorSettings.ADAPTIVE_STEP_MAX,
        sliderStep = MonitorSettings.ADAPTIVE_STEP_STEP,
        sliderDecimals = 2,
        sliderUnitRes = R.string.np_unit_times,
        sliderValueLabelRes = R.string.np_adaptive_step_title,
        dependsOn = MonitorSettings.KEY_ADAPTIVE_INTERVAL,
    ),
    OptionSpec(
        key = MonitorSettings.KEY_AUTO_START,
        type = OptionType.SWITCH,
        titleRes = R.string.np_monitor_autostart_title,
        summaryRes = R.string.np_monitor_autostart_summary,
        defaultBoolean = true,
    ),
    OptionSpec(
        key = TaskerGate.KEY_ENABLED,
        type = OptionType.SWITCH,
        titleRes = R.string.np_tasker_enabled_title,
        summaryRes = R.string.np_tasker_enabled_summary,
        defaultBoolean = TaskerGate.DEFAULT_ENABLED,
    ),
    OptionSpec(
        key = KEY_DATACARD_MANAGE,
        type = OptionType.ARROW,
        titleRes = R.string.np_datacard_manage_title,
        summaryRes = R.string.np_datacard_manage_summary,
    ),
    OptionSpec(
        key = KEY_ABOUT,
        type = OptionType.ARROW,
        titleRes = R.string.np_about_title,
        summaryRes = R.string.np_about_summary,
    ),
)


@Composable
private fun ModePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var subId by remember { mutableStateOf(-1) }
    var current by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val id = runCatching { NetPilot.defaultDataSubId() }.getOrDefault(-1)
        subId = id
        current = if (id >= 0) {
            runCatching { NetPilot.currentModeValue(id) }.getOrNull()
        } else {
            null
        }
    }

    val cur = current
    val pickerGroups = remember {
        NetworkMode.entries
            .groupBy { it.gen }
            .map { (gen, modes) -> gen to modes.distinctBy { it.label } }
            .sortedByDescending { it.first }
    }
    val carrierName = remember(subId) {
        if (subId >= 0) {
            runCatching { CarrierInfo.activeCarrierName(context, subId) }.getOrDefault("")
        } else {
            ""
        }
    }
    val autoCarrierLabel = if (carrierName.isNotEmpty()) {
        stringResource(R.string.np_mode_auto_carrier_named, carrierName)
    } else {
        stringResource(R.string.np_mode_auto_carrier)
    }
    val autoModeByGen = remember(subId) {
        if (subId < 0) {
            emptyMap()
        } else {
            listOf(5, 4, 3, 2).associateWith { gen ->
                runCatching { CarrierInfo.defaultModeForGen(context, subId, gen) }
                    .getOrDefault(-1)
            }
        }
    }

    fun writeMode(mode: NetworkMode) {
        if (busy || subId < 0) return
        busy = true
        scope.launch {
            val ok = runCatching { NetPilot.setMode(subId, mode) }.getOrDefault(false)
            val effective = NetPilot.lastModeWriteEffective
            busy = false
            if (ok) current = mode.value
            val text = when {
                !ok -> context.getString(R.string.np_mode_switch_failed, mode.label)
                !effective -> context.getString(R.string.np_mode_switch_no_effect, mode.label)
                else -> context.getString(R.string.np_mode_switch_applied, mode.label)
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    val summaryText = when {
        subId < 0 -> stringResource(R.string.np_mode_switch_none)
        cur == null -> stringResource(R.string.np_mode_switch_unknown)
        else -> stringResource(R.string.np_mode_switch_dialog_summary, NetworkMode.labelOf(cur))
    }

    WindowDialog(
        show = true,
        title = stringResource(R.string.np_mode_switch_dialog_title),
        summary = summaryText,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            pickerGroups.forEach { (gen, modes) ->
                MiuixText(
                    text = stringResource(genTitleRes(gen)),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModePickerRow(
                        label = autoCarrierLabel,
                        selected = cur != null && cur == autoModeByGen[gen],
                    ) {
                        val target = runCatching {
                            CarrierInfo.defaultModeForGen(context, subId, gen)
                        }.getOrDefault(26)
                        NetworkMode.fromValue(target)?.let { writeMode(it) }
                    }
                    modes.forEach { mode ->
                        ModePickerRow(label = mode.label, selected = mode.value == cur) {
                            writeMode(mode)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePickerRow(
    label: String,
    selected: Boolean,
    onPick: () -> Unit,
) {
    TextButton(
        text = (if (selected) "✓ " else "    ") + label,
        onClick = onPick,
        colors = if (selected) {
            ButtonDefaults.textButtonColorsPrimary()
        } else {
            ButtonDefaults.textButtonColors()
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun genTitleRes(gen: Int): Int = when (gen) {
    5 -> R.string.np_mode_group_5g
    4 -> R.string.np_mode_group_4g
    3 -> R.string.np_mode_group_3g
    else -> R.string.np_mode_group_2g
}
