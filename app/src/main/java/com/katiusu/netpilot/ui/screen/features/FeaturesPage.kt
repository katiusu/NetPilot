package com.katiusu.netpilot.ui.screen.features

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
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.monitor.CarrierInfo
import com.katiusu.netpilot.core.monitor.MonitorSettings
import com.katiusu.netpilot.core.tasker.TaskerGate
import com.katiusu.netpilot.prefs.OptionSpec
import com.katiusu.netpilot.prefs.OptionType
import com.katiusu.netpilot.ui.component.pref.HookOptionsPage
import com.katiusu.netpilot.ui.component.pref.HookSection
import com.katiusu.netpilot.ui.screen.about.AboutActivity
import com.katiusu.netpilot.ui.screen.datacard.DataCardActivity
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

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

/** 1.5.2：手动切制式的入口键（34 种内置制式，见 [NetworkMode]）。 */
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
    // 制式门控：修的是「真 4G 上 Ping 偏高 → 被判假满格 → 降级目标又是 4G →
    // 射频侧毫无变化、恢复计数却永远涨不上去 → 永久锁在 4G」这条链路。
    // 默认值只从 MonitorSettings 取，不在这里写死。
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
    // 1.5.2：磁贴那边只循环 4 种常用制式（用户明确要求不动磁贴），所以把「能切到全部
    // 内置制式」这件事做进界面：点开就是 34 种，按 5G/4G/3G/2G 分组。
    // 它是**一次性手动写入**，不参与自动降级的判定，也不改降级目标（KEY_DOWNGRADE_MODE 管那个）。
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
        // 2 dBm 已经窄到几乎不触发自适应，30 dBm 宽到会把间隔长期压在低位：
        // 两端都是「这个开关等于没有/等于常开」的边界，所以范围就取在它们之间。
        sliderMin = 2f,
        sliderMax = 30f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.np_unit_dbm,
        sliderValueLabelRes = R.string.np_adaptive_margin_title,
        dependsOn = MonitorSettings.KEY_ADAPTIVE_INTERVAL,
    ),
    // 「缩多快」与「缩到多低」是两件事：下限固定在 ADAPTIVE_MIN_FACTOR，
    // 每轮的缩短比例交给这个滑块。默认 0.85（= 每轮缩 15%）。
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


/**
 * 1.5.2 新增：把内置的 34 种制式列出来，点一条就写一条。
 *
 * 为什么放在功能页而不是磁贴：磁贴的交互只有「循环下一个」，34 种循环一遍要点 34 次，
 * 而且磁贴状态还得自己维护；用户的要求也是「把功能都做到界面里」。
 *
 * 写入仍然走 [NetPilot.setMode]，也就是和自动降级完全同一条特权链路（Root / Shizuku / 无通道
 * 的判定、失败原因回传、日志落盘一律不变）；这里只负责把「要写哪个值」选出来。
 * 写失败时不弹具体原因 —— 原因在日志页（`WriteDiag` 那套），对话框只提示去日志页看，
 * 免得把几百字的原始输出塞进一个 Toast。
 */
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
    // 1.5.3：列表里的两类合并。
    // 1) 每个代际组顶部补一行「自动适配运营商」：同一代际的自动模式会按插的哪张卡分成好几条
    //    （5G 的 26/27/32/33 连名字里都写着运营商），让用户自己挑本身就是错的设计，挑错更容易
    //    掉网；点按那一刻才按当前 SIM 的 MCC/MNC 解析出**该代际**真正要写的值。
    // 2) 0 与 3（都是「3G/2G 自动」）、10 与 22（都是「4G/3G/2G 自动 (全制式)」）底层是同一组
    //    位掩码（回读都会归一），列表里并排两行一模一样的文字只会让人以为其中一个不同，
    //    按 label 去重，保留枚举里靠前的那条。
    // 3) 合成行**不隐藏**任何原有条目：隐藏会让某些值在界面上再也选不到（4G 的 9 还是网络降级
    //    的默认目标），代价是当前值正好等于该运营商的默认档时，合成行与那一行会同时打勾 ——
    //    两行本来就指向同一个值，打两个勾不算说谎。
    val pickerGroups = remember {
        NetworkMode.entries
            .groupBy { it.gen }
            .map { (gen, modes) -> gen to modes.distinctBy { it.label } }
            .sortedByDescending { it.first }
    }
    // 把当前 SIM 认出来的运营商写在合成项上，用户才知道「自动适配」会挑哪一条。
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
    // 每个代际的解析结果，只用来决定合成行要不要打勾；点按时会重新解析一次（用户可能刚换卡）。
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

    // 点一行 → 写一次制式。抽成局部函数是因为 1.5.3 之后两类行共用它：普通制式项，
    // 以及需要先在点按那一刻解析出真值的「自动适配运营商」。
    fun writeMode(mode: NetworkMode) {
        if (busy || subId < 0) return
        busy = true
        scope.launch {
            val ok = runCatching { NetPilot.setMode(subId, mode) }.getOrDefault(false)
            // 1.5.4：写入成功 ≠ 生效（见 NetPilot.lastModeWriteEffective 的注释）。
            // 这里只把提示说诚实，返回值与判定一律不变。
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
                // 34 条一次铺开会顶到屏幕外，给内容一个高度上限 + 自己滚动。
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
                // 1.5.3：项与项之间留 6dp。改动前所有行紧挨在一起，相邻两项之间没有任何
                // 留白，点偏一格就会写错制式。
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 「自动适配运营商」：要写的那条值只有点按这一刻才知道（与自动降级恢复
                    // 时用的是同一张 MCC/MNC 表，按代际取一档）。兜底 26 就是 CarrierInfo 自己
                    // 文档里写的兜底值（读不到 SIM 时它返回 26），这里只防它抛异常；
                    // NetworkMode 的 FALLBACK 是私有的。
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

/**
 * 制式列表里的一行（1.5.3 抽出）。
 *
 * 抽出来是因为列表里多了一类**合成项**：「自动适配运营商」要等到点按那一刻才按 SIM 卡
 * 解析出真正的写入值，除此之外（勾选前缀、选中态配色、占满整行）与普通项完全一致。
 */
@Composable
private fun ModePickerRow(
    label: String,
    selected: Boolean,
    onPick: () -> Unit,
) {
    TextButton(
        // 「勾」只标记当前值，给一个固定宽度前缀让未选中项也左对齐。
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

/** 制式分组标题（按 [NetworkMode.gen] 分四档）。 */
private fun genTitleRes(gen: Int): Int = when (gen) {
    5 -> R.string.np_mode_group_5g
    4 -> R.string.np_mode_group_4g
    3 -> R.string.np_mode_group_3g
    else -> R.string.np_mode_group_2g
}
