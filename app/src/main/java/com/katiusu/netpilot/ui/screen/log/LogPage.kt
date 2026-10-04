package com.katiusu.netpilot.ui.screen.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.monitor.LogEntry
import com.katiusu.netpilot.core.monitor.LogLevel
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.ui.util.BlurredBar
import com.katiusu.netpilot.ui.util.blurSource
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import com.katiusu.netpilot.ui.util.rememberBlurState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * WARN 行用的琥珀色。
 *
 * Miuix 的调色板里只有 primary / error 这些语义色，没有 warning，所以这里沿用
 * 项目里已经用过的同一个字面量（HomePage 的状态文案也是这个色），保持一致。
 */
private val WarnColor = Color(0xFFE0A800)

/** 复制/分享时最多导出多少字符，避免把剪贴板和分享面板撑爆。 */
private const val MAX_EXPORT_CHARS = 200_000

/** 日志等级筛选档位。 */
private enum class LogFilter(val labelRes: Int) {
    ALL(R.string.log_filter_all),
    WARN_UP(R.string.log_filter_warn),
    ERROR_ONLY(R.string.log_filter_error),
}

/**
 * 日志页：按等级筛、清空、复制、分享，并始终跟在最新一条后面。
 *
 * 数据源刻意用 [LogStore.entries] 而不是 `NetPilot.logs()`：前者背后是
 * `mutableStateListOf`，遍历它本身就会在日志变化时触发重组；后者是取一次快照，
 * 页面会一直停在旧内容上。
 *
 * @param isBlurEnabled 是否启用顶栏模糊。
 * @param extraBottomPadding 额外的底部留白。
 */
@Composable
fun LogPageView(
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    val hazeState = rememberBlurState()
    val blurActive = isBlurEnabled && hazeState != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val allEntries = LogStore.entries

    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 这里刻意不用 remember { }：日志是 mutableStateListOf，用 remember 会把筛选结果缓存住，
    // 键不变就不会重算，新日志永远进不了列表。直接每次重组重算，400 条的上限完全扛得住。
    val entries = when (filter) {
        LogFilter.ALL -> allEntries.toList()
        LogFilter.WARN_UP -> allEntries.filter { it.level >= LogLevel.WARN }
        LogFilter.ERROR_ONLY -> allEntries.filter { it.level == LogLevel.ERROR }
    }

    // 有新日志就跟着滚到底。列表结构是「1 个头部 item + N 个日志 item」，
    // 所以最后一条的下标正好是 entries.size。
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size)
        }
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /** 拼一份纯文本，供剪贴板与分享共用。 */
    fun buildText(): String {
        val sb = StringBuilder()
        for (e in entries) {
            sb.append(e.timeText()).append(' ')
                .append(e.level.name).append(' ')
                .append(e.tag).append(": ")
                .append(e.message).append('\n')
            if (sb.length > MAX_EXPORT_CHARS) break
        }
        return sb.toString()
    }

    fun copyAll() {
        if (entries.isEmpty()) {
            toast(context.getString(R.string.log_toast_copy_empty))
            return
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast(context.getString(R.string.log_toast_copy_empty))
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("NetPilot", buildText()))
        toast(context.getString(R.string.log_toast_copied, entries.size))
    }

    fun shareAll() {
        if (entries.isEmpty()) {
            toast(context.getString(R.string.log_toast_copy_empty))
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_title))
            putExtra(Intent.EXTRA_TEXT, buildText())
            // 宿主可能是 Activity 也可能是别的 Context，带上 NEW_TASK 两边都不炸。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.log_action_share))
            )
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(hazeState, blurActive, scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.log_page_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = Modifier.blurSource(if (isBlurEnabled) hazeState else null)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                    ),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + extraBottomPadding,
                ),
            ) {
                // ---------- 头部：条数 + 筛选 + 复制/分享/清空 ----------
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        MiuixText(
                            text = stringResource(R.string.log_count, allEntries.size),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    // Miuix 没有 chip 组件，用 TextButton 组表达「选中」：
                                    // 选中的那档换成 primary 配色。
                                    LogFilter.entries.forEach { item ->
                                        TextButton(
                                            text = stringResource(item.labelRes),
                                            onClick = { filter = item },
                                            colors = if (filter == item) {
                                                ButtonDefaults.textButtonColorsPrimary()
                                            } else {
                                                ButtonDefaults.textButtonColors()
                                            },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    TextButton(
                                        text = stringResource(R.string.log_action_copy),
                                        onClick = { copyAll() },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(
                                        text = stringResource(R.string.log_action_share),
                                        onClick = { shareAll() },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(
                                        text = stringResource(R.string.log_action_clear),
                                        onClick = { showClearDialog = true },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.textButtonColorsPrimary(),
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------- 日志行 ----------
                if (entries.isEmpty()) {
                    item {
                        MiuixText(
                            text = stringResource(
                                if (allEntries.isEmpty()) {
                                    R.string.log_empty
                                } else {
                                    R.string.log_empty_filtered
                                }
                            ),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    items(entries) { entry ->
                        LogRow(entry)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }

            // 清空前的确认框：WindowDialog 自带窗口，放在 Box 里即可。
            if (showClearDialog) {
                WindowDialog(
                    show = showClearDialog,
                    title = stringResource(R.string.log_clear_dialog_title),
                    summary = stringResource(R.string.log_clear_dialog_summary),
                    onDismissRequest = { showClearDialog = false },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { showClearDialog = false },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            text = stringResource(R.string.log_action_clear),
                            onClick = {
                                NetPilot.clearLogs()
                                showClearDialog = false
                                toast(context.getString(R.string.log_toast_cleared))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}

/** 一条日志：第一行是时间 / 等级 / tag，第二行是正文（自动换行，不截断）。 */
@Composable
private fun LogRow(entry: LogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixText(
                text = entry.timeText(),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote2,
            )
            Spacer(Modifier.width(8.dp))
            MiuixText(
                text = entry.level.name,
                color = levelColor(entry.level),
                style = MiuixTheme.textStyles.footnote2,
            )
            Spacer(Modifier.width(8.dp))
            // tag 可能很长（包名），占满剩余宽度并省略，别把时间挤出去。
            MiuixText(
                text = entry.tag,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        MiuixText(
            text = entry.message,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body2,
        )
    }
}

/** 等级配色；WARN 见 [WarnColor] 的说明。 */
@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> MiuixTheme.colorScheme.error
    LogLevel.WARN -> WarnColor
    LogLevel.INFO -> MiuixTheme.colorScheme.onSurface
    LogLevel.DEBUG -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}
