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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.update.UpdateChecker
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 「发现新版本」对话框（1.6.0 按参考工程 HyperIsland 3.2.1 的 UpdateDialogHost 重写，MIT）。
 *
 * 相比旧版：
 * - 当前版本 / 最新版本改成两行**带标签**的对照，而不是挤在一句 summary 里；
 * - 版本对照与更新正文之间加分隔线，「更新内容」单独一行小标题，长 changelog 才看得出边界；
 * - changelog 维持纯文本：参考工程用的是它们自己的 Markdown 渲染器，本工程不为此新增依赖。
 *
 * 正文可能很长，用 [heightIn] 限高 + `verticalScroll`，避免对话框被撑破屏幕。
 * 「前往更新」是主按钮（[ButtonDefaults.textButtonColorsPrimary]），「稍后」是次要按钮。
 *
 * @param info 已经确认有新版（[UpdateChecker.UpdateInfo.hasUpdate] 为 true）的检查结果
 * @param onDismissRequest 点「稍后」/ 点外部 / 返回键
 * @param onConfirm 点「前往更新」——调用方负责用浏览器打开 [UpdateChecker.UpdateInfo.releaseUrl]
 */
@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.update_available_title),
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            VersionLine(
                label = stringResource(R.string.update_version_current),
                value = "v${info.currentVersion}",
                emphasize = false,
            )
            VersionLine(
                label = stringResource(R.string.update_version_latest),
                value = "v${info.latestVersion}",
                emphasize = true,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            if (info.releaseNotes.isNotBlank()) {
                MiuixText(
                    text = stringResource(R.string.update_notes_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                MiuixText(
                    text = info.releaseNotes,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 12.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.update_later),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.update_now),
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/**
 * 单行版本对照：左边小字标签，右边版本号（最新版本用主色强调）。
 */
@Composable
private fun VersionLine(label: String, value: String, emphasize: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MiuixText(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        MiuixText(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasize) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 通用提示对话框（检查更新失败 / 已是最新）。
 *
 * 为什么单独做一个而不是继续用 Toast：这两条结论在参考工程里是**弹窗**，
 * Toast 一闪而过，用户很容易错过「已是最新」这种需要确认的信息。
 *
 * @param dismissRes 确认按钮文案资源（默认「知道了」）
 */
@Composable
fun NoticeDialog(
    title: String,
    message: String,
    dismissRes: Int = R.string.update_ok,
    onDismissRequest: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MiuixText(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
            TextButton(
                text = stringResource(dismissRes),
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
