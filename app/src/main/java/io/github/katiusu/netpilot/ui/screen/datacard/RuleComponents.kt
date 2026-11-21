package io.github.katiusu.netpilot.ui.screen.datacard

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
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.datacard.DataCardRule
import io.github.katiusu.netpilot.core.datacard.SimSlotInfo
import io.github.katiusu.netpilot.core.mode.NetworkMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

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

            Spacer(Modifier.height(20.dp))

            SmallTitle(text = stringResource(R.string.dc_editor_group_action))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    GlassDropdownPreference(
            backdrop = LocalPreferenceBackdrop.current,
            renderInRootScaffold = true,
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
