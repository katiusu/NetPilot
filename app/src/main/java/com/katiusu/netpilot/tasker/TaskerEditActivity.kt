package com.katiusu.netpilot.tasker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.AppSettings
import com.katiusu.netpilot.LocaleHelper
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.core.tasker.LocalePluginContract
import com.katiusu.netpilot.core.tasker.TASKER_ACTION_SPECS
import com.katiusu.netpilot.core.tasker.TaskerActionSpec
import com.katiusu.netpilot.core.tasker.TaskerBridge
import com.katiusu.netpilot.core.tasker.TaskerContract
import com.katiusu.netpilot.ui.component.SubPageScaffold
import com.katiusu.netpilot.ui.theme.AppTheme
import com.katiusu.netpilot.ui.util.applyWindowBackground
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/** 本页面日志标签。 */
private const val TAG_EDIT = "Tasker插件"

/** 必须要有 subId 才能执行的动作。 */
private val ACTIONS_NEEDING_SUB_ID = setOf(
    TaskerContract.ACTION_SET_NETWORK_MODE,
    TaskerContract.ACTION_SET_PRESET,
    TaskerContract.ACTION_SET_DATA_SIM,
)

/**
 * Locale / Tasker 插件的配置界面。
 *
 * 进入方式：Tasker 里添加「插件 → NetPilot」时，Tasker 会带着
 * `com.twofortyfouram.locale.intent.action.EDIT_SETTING` 打开本 Activity；
 * 已有配置则通过 [LocalePluginContract.EXTRA_BUNDLE] 回填。
 *
 * 保存方式：`setResult(RESULT_OK, 带 EXTRA_BUNDLE + EXTRA_STRING_BLURB 的 Intent)`
 * 后 `finish()`。Tasker 会把这份 Bundle 存进它的任务里，执行时原样塞回
 * FIRE_SETTING / QUERY_CONDITION 广播，[com.katiusu.netpilot.core.tasker.LocaleFireReceiver]
 * 再交给与广播命令共用的执行器。
 *
 * 本页面不申请任何权限、不发起网络请求：它只负责拼一个纯基本类型的 Bundle。
 * 真正的特权动作（切制式 / 换卡）在执行时才需要 Root 或 Shizuku。
 */
class TaskerEditActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 事件出口在这里也补一次接线：用户打开配置页面时顺手保证订阅已建立。
        runCatching { TaskerBridge.init(this) }

        val settings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(settings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }
        applyWindowBackground(settings.themeMode)

        // 回填已有配置（Tasker 再次编辑时会带上）。
        val initial = LocalePluginContract.readBundle(intent)

        setContent {
            AppTheme(themeMode = themeMode) {
                SubPageScaffold(
                    title = stringResource(R.string.tasker_page_title),
                    isBlurEnabled = settings.isBlurEnabled,
                    onBack = { finish() },
                ) { padding ->
                    TaskerEditScreen(
                        initial = initial,
                        contentPadding = padding,
                        onCancel = { finish() },
                        onSave = { bundle, blurb -> finishWithResult(bundle, blurb) },
                    )
                }
            }
        }
    }

    /** 按 Locale 约定回传结果。 */
    private fun finishWithResult(bundle: Bundle, blurb: String) {
        runCatching {
            val data = Intent()
                .putExtra(LocalePluginContract.EXTRA_BUNDLE, bundle)
                .putExtra(LocalePluginContract.EXTRA_STRING_BLURB, blurb)
            setResult(RESULT_OK, data)
        }.onFailure { t ->
            LogStore.warn(TAG_EDIT, "回传插件配置失败：${t.message ?: t.javaClass.simpleName}")
        }
        finish()
    }
}

/** 一份拼好的插件配置：Bundle（交给 Tasker）+ 摘要（列表里显示）+ 校验错误。 */
private data class PluginConfig(
    val bundle: Bundle,
    val blurb: String,
    val error: String?,
)

@Composable
private fun TaskerEditScreen(
    initial: Bundle?,
    contentPadding: PaddingValues,
    onCancel: () -> Unit,
    onSave: (Bundle, String) -> Unit,
) {
    val context = LocalContext.current
    val specs = remember { TASKER_ACTION_SPECS }

    val initialAction = initial?.getString(TaskerContract.EXTRA_PLUGIN_ACTION)
    val initialCondition = initial?.getString(TaskerContract.EXTRA_PLUGIN_CONDITION)
    val initialSubId = initial?.getInt(TaskerContract.EXTRA_SUB_ID, -1) ?: -1

    // 打开时有条件没动作 → 认为上次配的是「判断条件」。
    var isCondition by remember { mutableStateOf(!initialCondition.isNullOrBlank()) }
    var actionIndex by remember {
        mutableStateOf(specs.indexOfFirst { it.action == initialAction }.takeIf { it >= 0 } ?: 0)
    }
    var conditionIndex by remember {
        mutableStateOf(
            TaskerContract.CONDITION_OPTIONS
                .indexOfFirst { it.first == initialCondition }
                .takeIf { it >= 0 } ?: 0
        )
    }
    var subIdText by remember {
        mutableStateOf(if (initialSubId >= 0) initialSubId.toString() else "")
    }
    var modeIndex by remember {
        mutableStateOf(
            TaskerContract.MODE_OPTIONS
                .indexOf(initial?.getInt(TaskerContract.EXTRA_MODE_VALUE, -1) ?: -1)
                .takeIf { it >= 0 } ?: 0
        )
    }
    var presetIndex by remember {
        mutableStateOf(
            TaskerContract.PRESET_OPTIONS
                .indexOfFirst { it.first == initial?.getString(TaskerContract.EXTRA_PRESET) }
                .takeIf { it >= 0 } ?: 0
        )
    }
    var enabled by remember {
        mutableStateOf(initial?.getBoolean(TaskerContract.EXTRA_ENABLED, true) ?: true)
    }
    var lock by remember {
        mutableStateOf(initial?.getBoolean(TaskerContract.EXTRA_LOCK, true) ?: true)
    }
    var showError by remember { mutableStateOf(false) }

    // 已启用的 SIM 卡：能读到就用下拉（避免手写 subId 写错），读不到退回输入框。
    val sims = remember { runCatching { NetPilot.sims(context) }.getOrDefault(emptyList()) }
    val simItems = remember(sims) {
        sims.map { "卡 ${it.slotIndex + 1} · ${it.title} · subId ${it.subId}" }
    }
    var simIndex by remember {
        mutableStateOf(sims.indexOfFirst { it.subId == initialSubId }.takeIf { it >= 0 } ?: 0)
    }
    val selectedSubId: Int = if (simItems.isNotEmpty()) {
        sims.getOrNull(simIndex)?.subId ?: -1
    } else {
        subIdText.trim().toIntOrNull() ?: -1
    }

    val spec = specs.getOrElse(actionIndex) { specs.first() }
    val modeValue = TaskerContract.MODE_OPTIONS.getOrElse(modeIndex) {
        TaskerContract.MODE_OPTIONS.first()
    }
    val presetKey = TaskerContract.PRESET_OPTIONS.getOrElse(presetIndex) {
        TaskerContract.PRESET_OPTIONS.first()
    }.first
    val conditionKey = TaskerContract.CONDITION_OPTIONS.getOrElse(conditionIndex) {
        TaskerContract.CONDITION_OPTIONS.first()
    }.first

    val config = buildPluginConfig(
        context = context,
        isCondition = isCondition,
        spec = spec,
        conditionIndex = conditionIndex,
        subId = selectedSubId,
        modeValue = modeValue,
        presetKey = presetKey,
        enabled = enabled,
        lock = lock,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- 类型 ----
        SmallTitle(text = stringResource(R.string.tasker_section_type))
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            WindowDropdownPreference(
                items = listOf(
                    stringResource(R.string.tasker_type_action),
                    stringResource(R.string.tasker_type_condition),
                ),
                selectedIndex = if (isCondition) 1 else 0,
                title = stringResource(R.string.tasker_type_title),
                summary = null,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
                onSelectedIndexChange = { index ->
                    isCondition = index == 1
                    showError = false
                },
            )
        }

        if (isCondition) {
            // ---- 条件 ----
            SmallTitle(text = stringResource(R.string.tasker_section_condition))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                WindowDropdownPreference(
                    items = TaskerContract.CONDITION_OPTIONS.map { it.second },
                    selectedIndex = conditionIndex,
                    title = stringResource(R.string.tasker_condition_title),
                    summary = null,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    onSelectedIndexChange = {
                        conditionIndex = it
                        showError = false
                    },
                )
            }
        } else {
            // ---- 动作 ----
            SmallTitle(text = stringResource(R.string.tasker_section_action))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                WindowDropdownPreference(
                    items = specs.map { it.title },
                    selectedIndex = actionIndex,
                    title = stringResource(R.string.tasker_action_title),
                    summary = stringResource(R.string.tasker_action_summary, spec.params),
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    onSelectedIndexChange = {
                        actionIndex = it
                        showError = false
                    },
                )
            }

            // ---- 参数 ----
            SmallTitle(text = stringResource(R.string.tasker_section_params))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column {
                    if (spec.action in ACTIONS_NEEDING_SUB_ID) {
                        if (simItems.isNotEmpty()) {
                            WindowDropdownPreference(
                                items = simItems,
                                selectedIndex = simIndex,
                                title = stringResource(R.string.tasker_param_sub_id_title),
                                summary = null,
                                enabled = true,
                                modifier = Modifier.fillMaxWidth(),
                                onSelectedIndexChange = { simIndex = it },
                            )
                        } else {
                            TextField(
                                value = subIdText,
                                onValueChange = { subIdText = it },
                                label = stringResource(R.string.tasker_param_sub_id_input),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            MiuixText(
                                text = stringResource(R.string.tasker_param_sub_id_fallback),
                                modifier = Modifier.padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    bottom = 8.dp,
                                ),
                            )
                        }
                    }

                    when (spec.action) {
                        TaskerContract.ACTION_SET_NETWORK_MODE -> {
                            WindowDropdownPreference(
                                items = TaskerContract.MODE_OPTIONS.map {
                                    "${TaskerContract.modeLabel(it)}（裸值 $it）"
                                },
                                selectedIndex = modeIndex,
                                title = stringResource(R.string.tasker_param_mode_title),
                                summary = null,
                                enabled = true,
                                modifier = Modifier.fillMaxWidth(),
                                onSelectedIndexChange = { modeIndex = it },
                            )
                        }

                        TaskerContract.ACTION_SET_PRESET -> {
                            WindowDropdownPreference(
                                items = TaskerContract.PRESET_OPTIONS.map {
                                    "${it.second}（${it.first}）"
                                },
                                selectedIndex = presetIndex,
                                title = stringResource(R.string.tasker_param_preset_title),
                                summary = null,
                                enabled = true,
                                modifier = Modifier.fillMaxWidth(),
                                onSelectedIndexChange = { presetIndex = it },
                            )
                        }

                        TaskerContract.ACTION_SET_AUTO_DOWNGRADE -> {
                            SwitchPreference(
                                title = stringResource(R.string.tasker_param_enabled_title),
                                summary = stringResource(R.string.tasker_param_enabled_summary),
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                                enabled = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        TaskerContract.ACTION_LOCK_LTE -> {
                            SwitchPreference(
                                title = stringResource(R.string.tasker_param_lock_title),
                                summary = stringResource(R.string.tasker_param_lock_summary),
                                checked = lock,
                                onCheckedChange = { lock = it },
                                enabled = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        else -> {
                            MiuixText(
                                text = stringResource(R.string.tasker_param_no_params),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }

            // ---- 示例 ----
            SmallTitle(text = stringResource(R.string.tasker_section_example))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixText(text = spec.example, modifier = Modifier.padding(16.dp))
            }
        }

        // ---- 预览 ----
        SmallTitle(text = stringResource(R.string.tasker_section_preview))
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            Column {
                MiuixText(
                    text = config.blurb.ifBlank { "—" },
                    modifier = Modifier.padding(16.dp),
                )
                if (showError && config.error != null) {
                    MiuixText(
                        text = config.error ?: "",
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }

        if (isCondition) {
            // 条件模式下把等价的 adb 命令也写出来，方便排错。
            SmallTitle(text = stringResource(R.string.tasker_section_example))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixText(
                    text = "adb shell am broadcast -a " +
                        LocalePluginContract.ACTION_QUERY_CONDITION +
                        " --es ${TaskerContract.EXTRA_PLUGIN_CONDITION} $conditionKey",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // ---- 说明 ----
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            MiuixText(
                text = stringResource(R.string.tasker_note_exported),
                modifier = Modifier.padding(16.dp),
            )
        }

        // ---- 按钮 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.tasker_cancel),
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (config.error != null) {
                        showError = true
                    } else {
                        onSave(config.bundle, config.blurb)
                    }
                },
                enabled = true,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f),
            ) {
                MiuixText(text = stringResource(R.string.tasker_save))
            }
        }
    }
}

/**
 * 把界面状态拼成 Locale 插件配置。
 *
 * 这里只放 String / Int / Boolean：Bundle 要跨进程交给 Tasker 保存，
 * 自定义对象 / Parcelable 是最容易在别的应用里反序列化失败的东西。
 */
private fun buildPluginConfig(
    context: Context,
    isCondition: Boolean,
    spec: TaskerActionSpec,
    conditionIndex: Int,
    subId: Int,
    modeValue: Int,
    presetKey: String,
    enabled: Boolean,
    lock: Boolean,
): PluginConfig {
    val bundle = Bundle()
    val prefix = context.getString(R.string.tasker_blurb_prefix)

    if (isCondition) {
        val condition = TaskerContract.CONDITION_OPTIONS.getOrElse(conditionIndex) {
            TaskerContract.CONDITION_OPTIONS.first()
        }
        bundle.putString(TaskerContract.EXTRA_PLUGIN_CONDITION, condition.first)
        val text = context.getString(R.string.tasker_blurb_condition, condition.second)
        return PluginConfig(bundle, prefix + text, null)
    }

    if (spec.action in ACTIONS_NEEDING_SUB_ID && subId < 0) {
        return PluginConfig(bundle, "", context.getString(R.string.tasker_error_sub_id))
    }

    bundle.putString(TaskerContract.EXTRA_PLUGIN_ACTION, spec.action)
    val blurb = when (spec.action) {
        TaskerContract.ACTION_SET_NETWORK_MODE -> {
            bundle.putInt(TaskerContract.EXTRA_SUB_ID, subId)
            bundle.putInt(TaskerContract.EXTRA_MODE_VALUE, modeValue)
            context.getString(
                R.string.tasker_blurb_set_mode,
                subId,
                TaskerContract.modeLabel(modeValue),
            )
        }

        TaskerContract.ACTION_SET_PRESET -> {
            bundle.putInt(TaskerContract.EXTRA_SUB_ID, subId)
            bundle.putString(TaskerContract.EXTRA_PRESET, presetKey)
            context.getString(R.string.tasker_blurb_set_preset, subId, presetKey)
        }

        TaskerContract.ACTION_TOGGLE_AUTO_DOWNGRADE ->
            context.getString(R.string.tasker_blurb_toggle_auto)

        TaskerContract.ACTION_SET_AUTO_DOWNGRADE -> {
            bundle.putBoolean(TaskerContract.EXTRA_ENABLED, enabled)
            context.getString(
                if (enabled) R.string.tasker_blurb_set_auto_on
                else R.string.tasker_blurb_set_auto_off
            )
        }

        TaskerContract.ACTION_SET_DATA_SIM -> {
            bundle.putInt(TaskerContract.EXTRA_SUB_ID, subId)
            context.getString(R.string.tasker_blurb_set_data_sim, subId)
        }

        TaskerContract.ACTION_SWITCH_DATA_SIM ->
            context.getString(R.string.tasker_blurb_switch_data_sim)

        TaskerContract.ACTION_LOCK_LTE -> {
            bundle.putBoolean(TaskerContract.EXTRA_LOCK, lock)
            context.getString(
                if (lock) R.string.tasker_blurb_lock_on else R.string.tasker_blurb_lock_off
            )
        }

        TaskerContract.ACTION_SAMPLE_NOW -> context.getString(R.string.tasker_blurb_sample_now)

        TaskerContract.ACTION_GET_STATUS -> context.getString(R.string.tasker_blurb_get_status)

        else -> spec.title
    }
    return PluginConfig(bundle, prefix + blurb, null)
}
