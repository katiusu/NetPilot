package com.katiusu.netpilot.ui.screen.features

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.monitor.MonitorSettings
import com.katiusu.netpilot.prefs.OptionSpec
import com.katiusu.netpilot.prefs.OptionType
import com.katiusu.netpilot.ui.component.pref.HookOptionsPage
import com.katiusu.netpilot.ui.component.pref.HookSection
import com.katiusu.netpilot.ui.screen.about.AboutActivity
import com.katiusu.netpilot.ui.screen.datacard.DataCardActivity

/**
 * 功能页：NetPilot 的全部可配置项。
 *
 * 布局交给通用组件 [HookOptionsPage]，配置项全部由 [OptionSpec] 驱动，
 * 这样「默认值只有一处定义」（[MonitorSettings]）——卡片只负责写 [com.katiusu.netpilot.prefs.ConfigState]。
 *
 * 注意：[featureSpecs] 的可见性是 `internal` 且被 `TemplateApp` 在启动时调用，
 * 改名或改可见性会直接导致编译失败。
 */
@Composable
fun FeaturesPageView(
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val specs = remember { featureSpecs() }
    val sections = remember(specs) { featureSections(specs) }

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
            }
        },
    )
}

private const val KEY_DATACARD_MANAGE = "np_datacard_manage"
private const val KEY_ABOUT = "np_about"

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
            MonitorSettings.KEY_WEAK_SIGNAL,
            MonitorSettings.KEY_WEAK_RSRP,
            MonitorSettings.KEY_TOGGLE_ENDC,
        ),
    ),
    HookSection(
        titleRes = R.string.np_section_policy,
        specs = specsByKeys(
            specs,
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
            MonitorSettings.KEY_AUTO_START,
        ),
    ),
    HookSection(
        titleRes = R.string.np_section_datacard,
        specs = listOf(specByKey(specs, KEY_DATACARD_MANAGE)),
    ),
    HookSection(
        titleRes = R.string.np_section_other,
        specs = listOf(specByKey(specs, KEY_ABOUT)),
    ),
)

/** 功能页的全部配置项（App 启动时注册，供全局搜索与作用域申请使用）。 */
internal fun featureSpecs(): List<OptionSpec> = listOf(
    OptionSpec(
        key = MonitorSettings.KEY_ENABLED,
        type = OptionType.SWITCH,
        titleRes = R.string.np_fake5g_enabled_title,
        summaryRes = R.string.np_fake5g_enabled_summary,
        // 与 MonitorSettings.DEFAULT_ENABLED 同一处真源：以前这里硬编码 false，
        // 引擎按 true 跑，界面却显示关着，用户以为没生效。
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
        key = MonitorSettings.KEY_WEAK_SIGNAL,
        type = OptionType.SWITCH,
        titleRes = R.string.np_fake5g_weak_signal_title,
        summaryRes = R.string.np_fake5g_weak_signal_summary,
        defaultBoolean = MonitorSettings.DEFAULT_WEAK_SIGNAL,
        dependsOn = MonitorSettings.KEY_ENABLED,
    ),
    // 弱信号阈值挂在「信号过差也降级」开关下：关掉那条规则时滑条自动收起，
    // 避免用户改了一个根本不生效的数字。写的是 Float（滑条），
    // 由 MonitorSettings.intValue 统一收敛成 Int。
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
        key = MonitorSettings.KEY_AUTO_START,
        type = OptionType.SWITCH,
        titleRes = R.string.np_monitor_autostart_title,
        summaryRes = R.string.np_monitor_autostart_summary,
        defaultBoolean = true,
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
