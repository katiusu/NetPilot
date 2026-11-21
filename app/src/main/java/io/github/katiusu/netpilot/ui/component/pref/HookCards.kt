package io.github.katiusu.netpilot.ui.component.pref

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.prefs.ConfigState
import io.github.katiusu.netpilot.prefs.OptionSpec
import io.github.katiusu.netpilot.bridge.HookStatusStore
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
private fun optionSummary(spec: OptionSpec): String? {
    val base = spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) }
    if (!spec.showStatus) return base
    val applied = rememberHookApplied(spec.key)
    val status = stringResource(
        if (applied) R.string.hook_status_applied else R.string.hook_status_not_applied
    )
    return listOfNotNull(base, status).joinToString(" · ")
}

@Composable
fun HookSwitchCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = rememberOptionEnabled(spec)
    val checked = ConfigState.bool(spec.key, spec.defaultBoolean)
    SwitchPreference(
        title = stringResource(spec.titleRes),
        summary = optionSummary(spec),
        checked = checked,
        onCheckedChange = {
            ConfigState.set(spec.key, it)
            HookStatusStore.removeKeys(context, listOf(spec.key))
            if (it) {
                ensureScopeFor(spec)
                recordOptionApplied(context, spec)
            }
        },
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
fun HookCheckboxCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = rememberOptionEnabled(spec)
    val checked = ConfigState.bool(spec.key, spec.defaultBoolean)
    CheckboxPreference(
        title = stringResource(spec.titleRes),
        summary = optionSummary(spec),
        checked = checked,
        onCheckedChange = {
            ConfigState.set(spec.key, it)
            HookStatusStore.removeKeys(context, listOf(spec.key))
            if (it) {
                ensureScopeFor(spec)
                recordOptionApplied(context, spec)
            }
        },
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
fun HookArrowCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val enabled = rememberOptionEnabled(spec)
    ArrowPreference(
        title = stringResource(spec.titleRes),
        summary = spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) },
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    )
}
