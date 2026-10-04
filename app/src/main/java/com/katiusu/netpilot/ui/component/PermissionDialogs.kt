package com.katiusu.netpilot.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.PermissionGuide
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 权限与首启引导的三个对话框（纯展示，判定逻辑在 [PermissionGuide]）。
 *
 * 统一约定：底部按钮行**左边取消 / 右边确认**，与用户原始反馈里明确要求的顺序一致。
 */

/**
 * 启动时的「权限理由说明」。
 *
 * 逐条列出「权限名 + 一句原因」——用户明确要求申请每个权限都要注明原因，
 * 所以这里不用笼统的一句话带过，reason 文案来自 [PermissionGuide.Item.reasonRes]。
 */
@Composable
fun PermissionRationaleDialog(
    items: List<PermissionGuide.Item>,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.perm_rationale_title),
        summary = stringResource(R.string.perm_rationale_summary),
        onDismissRequest = onCancel,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEach { item ->
                ReasonRow(
                    name = stringResource(item.nameRes),
                    reason = stringResource(item.reasonRes),
                )
            }
            ButtonRow(
                cancelText = stringResource(R.string.action_cancel),
                onCancel = onCancel,
                confirmText = stringResource(R.string.perm_action_continue),
                onConfirm = onContinue,
            )
        }
    }
}

/**
 * 有权限被拒时的说明框：列出被拒权限 + 会失去什么功能 + 「去设置」/「稍后」。
 *
 * 列表最多显示到 360dp 再滚动：低分辨率机型上三项原因加按钮会顶出屏幕。
 */
@Composable
fun PermissionDeniedDialog(
    items: List<PermissionGuide.Item>,
    onOpenSettings: () -> Unit,
    onLater: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.perm_denied_title),
        summary = stringResource(R.string.perm_denied_summary),
        onDismissRequest = onLater,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                items.forEach { item ->
                    ReasonRow(
                        name = stringResource(item.nameRes),
                        reason = stringResource(item.reasonRes),
                    )
                }
            }
            ButtonRow(
                cancelText = stringResource(R.string.perm_action_later),
                onCancel = onLater,
                confirmText = stringResource(R.string.perm_action_go_settings),
                onConfirm = onOpenSettings,
            )
        }
    }
}

/**
 * 首次打开时的「自启动 + 省电策略」引导。
 *
 * - 「去设置」走 [PermissionGuide.openStartupSettings]：先 MIUI/HyperOS 自启动管理页，不可用回落应用程式详情
 * - [onRequestIgnoreBatteryOptimizations] 为 null 时（已经忽略电池优化）不显示这条快捷入口；
 *   做成可空参数而不是在对话框里读系统状态，是为了让这里保持纯展示、判定仍在 PermissionGuide
 */
@Composable
fun FirstLaunchGuideDialog(
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: (() -> Unit)? = null,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.perm_first_launch_title),
        summary = stringResource(R.string.perm_first_launch_summary),
        onDismissRequest = onCancel,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ReasonRow(
                name = stringResource(R.string.perm_first_launch_autostart_name),
                reason = stringResource(R.string.perm_first_launch_autostart_reason),
            )
            ReasonRow(
                name = stringResource(R.string.perm_first_launch_battery_name),
                reason = stringResource(R.string.perm_first_launch_battery_reason),
            )
            MiuixText(
                text = stringResource(R.string.perm_first_launch_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (onRequestIgnoreBatteryOptimizations != null) {
                TextButton(
                    text = stringResource(R.string.perm_battery_ignore_action),
                    onClick = onRequestIgnoreBatteryOptimizations,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ButtonRow(
                cancelText = stringResource(R.string.action_cancel),
                onCancel = onCancel,
                confirmText = stringResource(R.string.perm_action_go_settings),
                onConfirm = onOpenSettings,
            )
        }
    }
}

/** 「权限名 + 原因」一行块：名字用正文，原因用脚注灰字，避免三段原因糊成一片。 */
@Composable
private fun ReasonRow(
    name: String,
    reason: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        MiuixText(
            text = "· $name",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        MiuixText(
            text = reason,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

/** 底部按钮行：左取消 / 右确认（确认用主色），与项目里其它确认框保持同一写法。 */
@Composable
private fun ButtonRow(
    cancelText: String,
    onCancel: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            text = cancelText,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
    }
}
