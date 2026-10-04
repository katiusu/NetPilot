package com.katiusu.netpilot.ui.screen.datacard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.datacard.DataCardRule
import com.katiusu.netpilot.core.datacard.SimSlotInfo
import com.katiusu.netpilot.core.mode.NetworkMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 规则编辑弹窗。
 *
 * 结构：匹配条件（名称 / SSID / BSSID）→ 命中后做什么（目标卡 / 优先级 / 冷却 / 回切）。
 * 每个可能让人看不懂的字段下面都跟一句解释 —— 用户反馈过「不知道这些项是干什么的」，
 * 尤其是「优先级」和「冷却时间」，所以这两项各有专门一行说明。
 */
@Composable
internal fun RuleEditorDialog(
    rule: DataCardRule,
    sims: List<SimSlotInfo>,
    onDismiss: () -> Unit,
    onSave: (DataCardRule) -> Unit,
) {
    var name by remember { mutableStateOf(rule.name) }
    var ssid by remember { mutableStateOf(rule.ssid) }
    var bssid by remember { mutableStateOf(rule.bssid) }
    var priority by remember { mutableStateOf(rule.priority.toString()) }
    var cooldown by remember { mutableStateOf(rule.cooldownSec.toString()) }
    var revert by remember { mutableStateOf(rule.revertOnLeave) }
    var targetIndex by remember {
        mutableStateOf(
            sims.indexOfFirst { it.subId == rule.targetSubId }.let { if (it < 0) 0 else it + 1 }
        )
    }

    val anySim = stringResource(R.string.np_dc_any_sim)
    val targetOptions = remember(sims, anySim) {
        listOf(anySim) + sims.map { "${it.slotIndex + 1} · ${it.title}" }
    }

    WindowDialog(
        show = true,
        title = stringResource(R.string.np_dc_rule_edit),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SmallTitle(text = stringResource(R.string.dc_editor_group_match))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.np_dc_rule_name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    TextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = stringResource(R.string.np_dc_rule_ssid),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    TextField(
                        value = bssid,
                        onValueChange = { bssid = it },
                        label = stringResource(R.string.np_dc_rule_bssid),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    MiuixText(
                        text = stringResource(R.string.dc_editor_match_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }

            // 组间距 20.dp > 组内 14.dp：两个分组在视觉上明显分开。
            Spacer(Modifier.height(20.dp))

            SmallTitle(text = stringResource(R.string.dc_editor_group_action))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    WindowDropdownPreference(
                        items = targetOptions,
                        selectedIndex = targetIndex,
                        title = stringResource(R.string.np_dc_rule_target),
                        summary = null,
                        onSelectedIndexChange = { targetIndex = it },
                    )
                    MiuixText(
                        text = stringResource(R.string.dc_editor_target_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    TextField(
                        value = priority,
                        onValueChange = { priority = it.filter { c -> c.isDigit() } },
                        label = stringResource(R.string.np_dc_rule_priority),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MiuixText(
                        text = stringResource(R.string.dc_editor_priority_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    TextField(
                        value = cooldown,
                        onValueChange = { cooldown = it.filter { c -> c.isDigit() } },
                        label = stringResource(R.string.np_dc_rule_cooldown),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MiuixText(
                        text = stringResource(R.string.dc_editor_cooldown_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                    )
                    SwitchPreference(
                        title = stringResource(R.string.np_dc_rule_revert),
                        summary = stringResource(R.string.dc_editor_revert_hint),
                        checked = revert,
                        onCheckedChange = { revert = it },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.action_confirm),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val targetSub = if (targetIndex <= 0) {
                            -1
                        } else {
                            sims.getOrNull(targetIndex - 1)?.subId ?: -1
                        }
                        onSave(
                            rule.copy(
                                name = name.trim().ifBlank { rule.name },
                                ssid = ssid.trim(),
                                bssid = bssid.trim().lowercase(),
                                priority = priority.toIntOrNull() ?: 0,
                                cooldownSec = (cooldown.toIntOrNull() ?: 120).coerceIn(0, 3600),
                                revertOnLeave = revert,
                                targetSubId = targetSub,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

internal fun ruleTitle(rule: DataCardRule): String =
    rule.name.ifBlank { if (rule.ssid.isBlank()) "Wi-Fi" else rule.ssid }

/**
 * 匹配条件摘要：这条规则「在什么条件下会被命中」。
 *
 * 加上优先级与冷却，用户不用打开弹窗就能知道这条规则的脾气。
 */
@Composable
internal fun ruleMatchSummary(rule: DataCardRule): String {
    val wifi = if (rule.ssid.isBlank() && rule.bssid.isBlank()) {
        stringResource(R.string.np_dc_rule_any_wifi)
    } else {
        listOf(rule.ssid, rule.bssid).filter { it.isNotBlank() }.joinToString(" / ")
    }
    val head = stringResource(R.string.dc_rule_summary, wifi, rule.priority, rule.cooldownSec)
    return if (rule.revertOnLeave) head + stringResource(R.string.dc_rule_summary_revert) else head
}

/**
 * 规则库列表里的摘要：匹配条件 + 命中后切到哪张卡 + 是否顺带改目标卡制式。
 *
 * 规则自带目标卡，所以这三行就是这条规则的全部行为，不需要再去别的卡里找订阅关系。
 */
@Composable
internal fun ruleLibrarySummary(rule: DataCardRule, sims: List<SimSlotInfo>): String {
    val target = sims.firstOrNull { it.subId == rule.targetSubId }
    val targetText = if (target != null) {
        stringResource(R.string.dc_rule_target_sim, target.slotIndex + 1)
    } else {
        stringResource(R.string.dc_rule_target_none)
    }
    val modeText = if (rule.targetModeValue > 0) {
        val label = NetworkMode.fromValue(rule.targetModeValue)?.label
            ?: rule.targetModeValue.toString()
        stringResource(R.string.dc_rule_mode_set, label)
    } else {
        stringResource(R.string.dc_rule_mode_keep)
    }
    return listOf(ruleMatchSummary(rule), targetText, modeText).joinToString("\n")
}
