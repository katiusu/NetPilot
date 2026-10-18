package com.katiusu.netpilot.ui.screen.log

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Icon
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
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
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
    /** 不筛等级。 */
    ALL(R.string.log_filter_all),

    DEBUG_ONLY(R.string.log_filter_debug),
    INFO_ONLY(R.string.log_filter_info),

    /** 警告及以上（含错误）。 */
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

    // 1.6.0 新增：关键字搜索 + 输入框展开态。
    // 为什么放在「筛选」之外单独存：搜索是「看哪几条」，筛选是「看哪一档」，两者是可叠加的独立维度。
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }

    // 1.5.2：默认正序（旧→新）—— 与 LogStore 的追加顺序一致；倒序留给排查场景。
    var order by remember { mutableStateOf(LogOrder.ASC) }
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 这里刻意不用 remember { }：日志是 mutableStateListOf，用 remember 会把筛选结果缓存住，
    // 键不变就不会重算，新日志永远进不了列表。直接每次重组重算，400 条的上限完全扛得住。
    val filtered = when (filter) {
        LogFilter.ALL -> allEntries.toList()
        LogFilter.DEBUG_ONLY -> allEntries.filter { it.level == LogLevel.DEBUG }
        LogFilter.INFO_ONLY -> allEntries.filter { it.level == LogLevel.INFO }
        LogFilter.WARN_UP -> allEntries.filter { it.level >= LogLevel.WARN }
        LogFilter.ERROR_ONLY -> allEntries.filter { it.level == LogLevel.ERROR }
    }.let { list ->
        // 关键字同时搜 tag 与正文：报障时手里往往只有「某个包名」或「某句报错」，两处都得能搜到。
        val q = query.trim()
        if (q.isEmpty()) list else list.filter {
            it.tag.contains(q, ignoreCase = true) || it.message.contains(q, ignoreCase = true)
        }
    }
    // 底层 LogStore.entries 始终是追加式的旧→新；这里只按显示顺序翻一次，
    // 所以过滤、导出、落盘的语义都没有动。
    val entries = when (order) {
        LogOrder.ASC -> filtered
        LogOrder.DESC -> filtered.asReversed()
    }

    // 1.6.0：不再自动跳最新。原来这里按 (entries.size, order) 滚到最新端 —— 于是
    // 「点一下倒序」「换个筛选档」也会把位置甩到最新一条，而那时用户往往正在往回翻
    // 某段历史日志。现在列表位置只由用户自己的滚动决定。

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

    // 右上角「更多」菜单（1.6.0 调整）：直接用 Miuix 的下拉菜单，结构照参照工程
    // HyperIsland 3.2.1（MIT）的 AppsPage —— 排序做成两个互斥勾选项，其余是动作项。
    // 为什么用 **Window** 变体：Overlay 变体把弹层挂到「根 Scaffold」的 overlay 上，而本页的
    // Scaffold 处在一个 HorizontalPager 页里，实测点了没反应；Window 变体是独立窗口，
    // 不依赖页面层级（参照工程是单 Scaffold 应用，所以它用 Overlay 没问题）。
    val menuEntries = listOf(
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = stringResource(R.string.log_order_asc),
                    selected = order == LogOrder.ASC,
                    onClick = { order = LogOrder.ASC },
                ),
                DropdownItem(
                    text = stringResource(R.string.log_order_desc),
                    selected = order == LogOrder.DESC,
                    onClick = { order = LogOrder.DESC },
                ),
            ),
        ),
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = stringResource(R.string.log_action_copy),
                    onClick = { copyAll() },
                ),
                DropdownItem(
                    text = stringResource(R.string.log_action_share),
                    onClick = { shareAll() },
                ),
                DropdownItem(
                    text = stringResource(R.string.log_action_export),
                    onClick = { exportAll() },
                ),
                DropdownItem(
                    text = stringResource(R.string.log_action_clear),
                    onClick = { showClearDialog = true },
                ),
            ),
        ),
    )

    Scaffold(
        topBar = {
            BlurredBar(hazeState, blurActive, scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.log_page_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        WindowIconDropdownMenu(
                            entries = menuEntries,
                            collapseOnSelection = true,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.More,
                                contentDescription = stringResource(R.string.log_action_more),
                            )
                        }
                    },
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
                // ---------- 头部：搜索 + 等级筛选 + 汇总 ----------
                // 排序 / 复制 / 分享 / 导出 / 清空都在右上角「更多」菜单里（1.6.0 调整）。
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        // 搜索框放在最上面（照参照工程 HyperAutofillFix, MIT 的日志页）：
                        // 排查时第一件事通常是搜某个 tag 或某句报错，而不是先挑筛选档位。
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            SearchBar(
                                inputField = {
                                    InputField(
                                        query = query,
                                        onQueryChange = { query = it },
                                        onSearch = { searchExpanded = false },
                                        expanded = searchExpanded,
                                        onExpandedChange = { searchExpanded = it },
                                        label = stringResource(R.string.log_search_hint),
                                    )
                                },
                                // 这三个参数在 Miuix 0.9.4 里都是必填/半必填：
                                // 输入框的展开态由页面持有（不是组件内部状态），content 是展开后的建议区。
                                expanded = searchExpanded,
                                onExpandedChange = { searchExpanded = it },
                                content = {},
                            )
                        }
                        // 卡一：等级筛选 + 汇总。档位是互斥单选，用真正的 TabRow 表达。
                        Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Column {
                                TabRow(
                                    tabs = LogFilter.entries.map { stringResource(it.labelRes) },
                                    selectedTabIndex = LogFilter.entries.indexOf(filter),
                                    onTabSelected = { index -> filter = LogFilter.entries[index] },
                                )
                            }
                        }
                        // 卡二：日志档位（简要/详细）。**独立卡片**而不是并进筛选卡 ——
                        // 它说明的是「这份日志是什么口径」，不是一个可点的筛选档位；
                        // 挤在同一张卡里会被当成第四个标签。
                        Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                MiuixText(
                                    text = stringResource(
                                        R.string.log_mode_line,
                                        stringResource(modeLabelRes()),
                                    ),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote2,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                if (WriteDiag.mode != WriteDiag.Mode.DETAILED) {
                                    MiuixText(
                                        text = stringResource(R.string.log_mode_brief_hint),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        style = MiuixTheme.textStyles.footnote2,
                                        modifier = Modifier.padding(
                                            start = 16.dp,
                                            end = 16.dp,
                                            top = 4.dp,
                                        ),
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
                                when {
                                    allEntries.isEmpty() -> R.string.log_empty
                                    query.isNotBlank() -> R.string.log_search_empty
                                    else -> R.string.log_empty_filtered
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
 * 一条日志。
 *
 * 布局照参照工程（HyperAutofillFix, MIT）的日志页：左侧圆点表等级、标题是等级名、
 * 摘要行「时间 · tag」、正文用等宽字体放在行底。等宽不是审美 —— 诊断日志里全是
 * `subId=1 mode=27 -> 842751` 这种数字，等宽列对齐后才扫得动。
 *
 * 整行可点：点一下只复制这一条 —— 报问题的人通常要的是「出问题那一行」，不是全部 200 条。
 */
@Composable
private fun LogRow(entry: LogEntry, onClick: () -> Unit) {
    BasicComponent(
        title = entry.level.name,
        // tag 可能很长（包名），由 BasicComponent 的 summary 自己截断；时间放在最前面，
        // 因为它才是排查时用来对齐「什么时候发生」的锚点。
        summary = entry.timeText() + " · " + entry.tag,
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(levelColor(entry.level)),
            )
        },
        bottomAction = {
            MiuixText(
                text = entry.message,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body2,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        onClick = onClick,
    )
}

/** 等级配色；WARN 见 [WarnColor] 的说明。 */
@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> MiuixTheme.colorScheme.error
    LogLevel.WARN -> WarnColor
    LogLevel.INFO -> MiuixTheme.colorScheme.onSurface
    LogLevel.DEBUG -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}
