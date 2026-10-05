package com.katiusu.netpilot.ui.screen.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import com.katiusu.netpilot.core.priv.WriteDiag
import com.katiusu.netpilot.ui.util.BlurredBar
import com.katiusu.netpilot.ui.util.blurSource
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import com.katiusu.netpilot.ui.util.rememberBlurState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
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

/**
 * 导出到**文件**时的字符上限，比剪贴板那条宽松得多。
 *
 * 为什么单独给一个上限：详细模式下一条 `content query` 的原始输出就可能上千字符，
 * 用剪贴板那个 20 万的上限会把排障最关键的后半段砍掉。写文件没有分享面板的体积顾虑。
 */
private const val MAX_FILE_CHARS = 4_000_000

/** 日志等级筛选档位。 */
private enum class LogFilter(val labelRes: Int) {
    ALL(R.string.log_filter_all),
    WARN_UP(R.string.log_filter_warn),
    ERROR_ONLY(R.string.log_filter_error),
}

/**
 * 日志的显示顺序（1.5.2 新增）。
 *
 * 为什么替掉原来那一档「详细」筛选：详细/简要的差别是「**记录什么**」，它由设置页的开关决定，
 * 在这里再筛一次只会让人误以为「日志丢了」；日志页真正需要的开关是「先看旧还是先看新」。
 */
private enum class LogOrder(val labelRes: Int) {
    /** 旧 → 新（默认）：新日志追加在底部，和写入顺序一致。 */
    ASC(R.string.log_order_asc),

    /** 新 → 旧：排查时先看「刚刚发生了什么」。 */
    DESC(R.string.log_order_desc),
}

/**
 * 日志页：按等级筛、清空、复制、分享、导出成文件，始终停在最新一条上。
 *
 * 数据源刻意用 [LogStore.entries] 而不是 `NetPilot.logs()`：前者背后是
 * `mutableStateListOf`，遍历它本身就会在日志变化时触发重组；后者是取一次快照，
 * 页面会一直停在旧内容上。
 *
 * 显示顺序可在「正序（旧→新，默认）」与「倒序（新→旧）」之间切换；无论界面怎么排，
 * 导出（剪贴板 / 分享 / 文件）都固定按时间正序，读起来才顺。
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

    // 1.5.2：默认正序（旧→新）—— 与 LogStore 的追加顺序一致；倒序留给排查场景。
    var order by remember { mutableStateOf(LogOrder.ASC) }
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 这里刻意不用 remember { }：日志是 mutableStateListOf，用 remember 会把筛选结果缓存住，
    // 键不变就不会重算，新日志永远进不了列表。直接每次重组重算，400 条的上限完全扛得住。
    val filtered = when (filter) {
        LogFilter.ALL -> allEntries.toList()
        LogFilter.WARN_UP -> allEntries.filter { it.level >= LogLevel.WARN }
        LogFilter.ERROR_ONLY -> allEntries.filter { it.level == LogLevel.ERROR }
    }
    // 底层 LogStore.entries 始终是追加式的旧→新；这里只按显示顺序翻一次，
    // 所以过滤、导出、落盘的语义都没有动。
    val entries = when (order) {
        LogOrder.ASC -> filtered
        LogOrder.DESC -> filtered.asReversed()
    }

    // 有新日志就停在最新端。列表结构是「1 个头部 item + N 个日志 item」：
    // 正序时最新的在最后（第 entries.size 号），倒序时最新的在最前（第 1 号）。
    LaunchedEffect(entries.size, order) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(if (order == LogOrder.ASC) entries.size else 1)
        }
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /** 一条日志的单行形态：界面、剪贴板、文件导出共用，避免三处各写一遍格式。 */
    fun oneLine(entry: LogEntry): String =
        entry.timeText() + " " + entry.level.name + " " + entry.tag + ": " + entry.message

    /** 拼一份纯文本，供剪贴板、分享与文件导出共用（时间正序）。 */
    fun buildText(maxChars: Int = MAX_EXPORT_CHARS): String {
        val sb = StringBuilder()
        // 界面可能被切成倒序（新→旧），但导出的文本按时间顺序（旧→新）更好读，这里翻回来一次。
        val list = if (order == LogOrder.ASC) entries else entries.asReversed()
        for (e in list) {
            sb.append(oneLine(e)).append('\n')
            if (sb.length > maxChars) break
        }
        return sb.toString()
    }

    /** 当前档位对应的资源 id（档位名在本进程缓存里，取一次即可）。 */
    fun modeLabelRes(): Int = if (WriteDiag.mode == WriteDiag.Mode.DETAILED) {
        R.string.log_mode_detailed
    } else {
        R.string.log_mode_brief
    }

    /**
     * 导出文件的头部（5 行）：先把「这份日志是谁、哪一档、什么筛选」写清楚。
     *
     * 为什么头部要带这些：导出的文件会被贴到别处（issue、聊天窗口）看，
     * 「简要模式」四个字能立刻说明「为什么没有逐步过程」—— 免得读的人以为日志被截断了。
     *
     * 为什么单独抽出来：导出改成**流式**逐条写文件了（见 exportLauncher 处的注释），
     * 头部先写、正文边遍历边写，不再先把整份日志拼成一个 String。
     */
    fun fileHeader(): String {
        val sb = StringBuilder()
        sb.append("# NetPilot 运行日志\n")
        sb.append("# 导出时间：")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append('\n')
        sb.append("# 日志模式：").append(context.getString(modeLabelRes())).append('\n')
        sb.append("# 界面筛选：").append(context.getString(filter.labelRes))
            .append("；本次导出 ").append(entries.size).append(" 条\n")
        sb.append("# 说明：简要模式含结论、失败原因与失败时系统返回的原始值；")
            .append("详细模式另含逐候选、逐步的完整原始输出。\n\n")
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

    /** 点按某一条：只复制这一条。排查时更常用的是「把出问题的那一行发出去」。 */
    fun copyOne(entry: LogEntry) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast(context.getString(R.string.log_toast_copy_empty))
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("NetPilot", oneLine(entry)))
        toast(context.getString(R.string.log_toast_copied_one))
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

    // 系统文件选择器（SAF）：用户自己挑目录，本应用不需要任何存储权限，
    // 也不用把日志写进被策略只读保护的目录。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("openOutputStream 返回 null")
                // 逐条写文件，不先把整份日志拼成一个 String 再转 byte[]：
                // 旧写法要同时持有 StringBuilder 的 char[] + toString() 的 String +
                // toByteArray(UTF_8) 的 byte[]，导出上限 4,000,000 字符时峰值 4~5 MB，
                // 而且整段都跑在主线程（SAF 回调）。改成流式后峰值只跟「一行」有关。
                // 输出与旧写法逐字节一致：同样的 5 行头部 + 同样的截断口径 ——
                // 旧口径是「追加完一行再判断长度超限」，这里保持一样。
                stream.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.append(fileHeader())
                    val list = if (order == LogOrder.ASC) entries else entries.asReversed()
                    var written = 0
                    for (e in list) {
                        val line = oneLine(e)
                        w.append(line).append('\n')
                        written += line.length + 1
                        if (written > MAX_FILE_CHARS) break
                    }
                }
            }
            if (result.isSuccess) {
                toast(context.getString(R.string.log_toast_exported, uri.lastPathSegment.orEmpty()))
            } else {
                toast(
                    context.getString(
                        R.string.log_toast_export_failed,
                        result.exceptionOrNull()?.message.orEmpty(),
                    )
                )
            }
        }
    }

    fun exportAll() {
        if (entries.isEmpty()) {
            toast(context.getString(R.string.log_toast_copy_empty))
            return
        }
        // 文件名带时间戳：多次导出不会在同一个目录里互相覆盖。
        val name = "NetPilot-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        runCatching { exportLauncher.launch(name) }.onFailure {
            toast(context.getString(R.string.log_toast_export_failed, it.message.orEmpty()))
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
                // ---------- 头部：条数 + 当前档位 + 筛选 + 复制/分享/导出/清空 ----------
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        MiuixText(
                            text = stringResource(R.string.log_count, allEntries.size),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                        // 当前档位必须写出来：拿到一份只记结论的日志时，先要看的就是它是不是简要模式
                        // （那决定了「没有逐步过程」是设计如此，还是日志真丢了）。
                        MiuixText(
                            text = stringResource(R.string.log_mode_line, stringResource(modeLabelRes())),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                        if (WriteDiag.mode != WriteDiag.Mode.DETAILED) {
                            MiuixText(
                                text = stringResource(R.string.log_mode_brief_hint),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote2,
                                modifier = Modifier
                                    .padding(horizontal = 28.dp)
                                    .padding(top = 2.dp, bottom = 6.dp),
                            )
                        }
                        Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Column {
                                // 显示顺序切换：图标用 Miuix 图标库里的 Sort，文字显示当前档位，点按即切换。
                                // 单独占一行、不跟筛选挤在横向滚动里 —— 它是「怎么看」，不是「看什么」。
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = {
                                            order = if (order == LogOrder.ASC) {
                                                LogOrder.DESC
                                            } else {
                                                LogOrder.ASC
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.Sort,
                                            contentDescription = stringResource(
                                                R.string.log_order_line,
                                                stringResource(order.labelRes),
                                            ),
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    MiuixText(
                                        text = stringResource(
                                            R.string.log_order_line,
                                            stringResource(order.labelRes),
                                        ),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        style = MiuixTheme.textStyles.footnote2,
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                                // 三档横排：窄屏放不下，所以这一行可以横向滚动。
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
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
                                // 复制 / 分享 / 导出文件
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
                                        text = stringResource(R.string.log_action_export),
                                        onClick = { exportAll() },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                                // 清空单独一行：它是破坏性操作，不该和上面三个挤在同一行的同一层级里避免误触
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
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
                        LogRow(entry = entry, onClick = { copyOne(entry) })
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

/**
 * 一条日志：第一行是时间 / 等级 / tag，第二行是正文（自动换行，不截断）。
 *
 * 整行可点：点一下只复制这一条 —— 报问题的人通常要的是「出问题那一行」，不是全部 200 条。
 */
@Composable
private fun LogRow(entry: LogEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
