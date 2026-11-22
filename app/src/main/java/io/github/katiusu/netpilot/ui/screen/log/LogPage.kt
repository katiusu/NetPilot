package io.github.katiusu.netpilot.ui.screen.log

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import io.github.katiusu.netpilot.ui.component.AppTopAppBar
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.ui.component.isGlassTopBarAvailable
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.monitor.LogEntry
import io.github.katiusu.netpilot.core.monitor.LogLevel
import io.github.katiusu.netpilot.core.monitor.LogStore
import io.github.katiusu.netpilot.core.priv.WriteDiag
import io.github.katiusu.netpilot.ui.util.BlurredBar
import io.github.katiusu.netpilot.ui.util.PageBackdrop
import io.github.katiusu.netpilot.ui.util.pageScrollModifiers
import io.github.katiusu.netpilot.ui.util.TopLayer
import io.github.katiusu.netpilot.ui.util.rememberBlurBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassIconDropdownMenu
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val WarnColor = Color(0xFFE0A800)

private const val MAX_EXPORT_CHARS = 200_000

private const val MAX_FILE_CHARS = 4_000_000

private enum class LogFilter(val labelRes: Int) {
    ALL(R.string.log_filter_all),

    DEBUG_ONLY(R.string.log_filter_debug),
    INFO_ONLY(R.string.log_filter_info),

    WARN_UP(R.string.log_filter_warn),

    ERROR_ONLY(R.string.log_filter_error),
}

private enum class LogOrder(val labelRes: Int) {
    ASC(R.string.log_order_asc),

    DESC(R.string.log_order_desc),
}

@Composable
fun LogPageView(
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    val backdrop = rememberBlurBackdrop()
    val blurActive = isBlurEnabled && backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    // 玻璃菜单要自己记展开态与锚点；玻璃不可用时同一份 menuEntries 走窗口菜单，两条分支共用排序/清空逻辑。
    var showMoreMenu by remember { mutableStateOf(false) }
    val moreAnchor = rememberGlassPopupAnchor()
    // 顶栏是否玻璃与菜单是否玻璃必须同判：RuntimeShader 不可用时顶栏会退成普通样式，
    // 菜单若还走玻璃分支，会出现「没有模糊底的玻璃菜单」甚至渲染异常。
    val glassMenu = isGlassTopBarAvailable() && blurActive

    // LogStore.entries 现在是 StateFlow：collectAsState 订阅后拿到的就是不可变快照，
    // 后台线程 append 也会触发重组（与原来的 SnapshotStateList 行为一致）。
    val allEntries = LogStore.entries.collectAsState().value

    var filter by remember { mutableStateOf(LogFilter.ALL) }

    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }

    var order by remember { mutableStateOf(LogOrder.ASC) }
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val filtered = when (filter) {
        LogFilter.ALL -> allEntries.toList()
        LogFilter.DEBUG_ONLY -> allEntries.filter { it.level == LogLevel.DEBUG }
        LogFilter.INFO_ONLY -> allEntries.filter { it.level == LogLevel.INFO }
        LogFilter.WARN_UP -> allEntries.filter { it.level >= LogLevel.WARN }
        LogFilter.ERROR_ONLY -> allEntries.filter { it.level == LogLevel.ERROR }
    }.let { list ->
        val q = query.trim()
        if (q.isEmpty()) list else list.filter {
            it.tag.contains(q, ignoreCase = true) || it.message.contains(q, ignoreCase = true)
        }
    }
    val entries = when (order) {
        LogOrder.ASC -> filtered
        LogOrder.DESC -> filtered.asReversed()
    }


    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun oneLine(entry: LogEntry): String =
        entry.timeText() + " " + entry.level.name + " " + entry.tag + ": " + entry.message

    fun buildText(maxChars: Int = MAX_EXPORT_CHARS): String {
        val sb = StringBuilder()
        val list = entries
        for (e in list) {
            sb.append(oneLine(e)).append('\n')
            if (sb.length > maxChars) break
        }
        return sb.toString()
    }

    fun modeLabelRes(): Int = if (WriteDiag.mode == WriteDiag.Mode.DETAILED) {
        R.string.log_mode_detailed
    } else {
        R.string.log_mode_brief
    }

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
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.log_action_share))
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("openOutputStream 返回 null")
                stream.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.append(fileHeader())
                    val list = entries
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
        val name = "NetPilot-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        runCatching { exportLauncher.launch(name) }.onFailure {
            toast(context.getString(R.string.log_toast_export_failed, it.message.orEmpty()))
        }
    }

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
            BlurredBar(backdrop = if (isBlurEnabled) backdrop else null, blurEnabled = isBlurEnabled) {
                AppTopAppBar(
                    title = stringResource(R.string.log_page_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (glassMenu) {
                            GlassIconButton(
                                onClick = { showMoreMenu = !showMoreMenu },
                                modifier = Modifier.glassPopupAnchor(
                                    anchor = moreAnchor,
                                    cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2,
                                    floating = true,
                                ),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Glass.More,
                                    contentDescription = stringResource(R.string.log_action_more),
                                    modifier = Modifier
                                        .size(28.dp)
                                        .glassPopupAnchorContent(moreAnchor),
                                )
                            }
                        } else {
                            WindowIconDropdownMenu(
                                entries = menuEntries,
                                collapseOnSelection = true,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.More,
                                    contentDescription = stringResource(R.string.log_action_more),
                                )
                            }
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        PageBackdrop(backdrop = if (isBlurEnabled) backdrop else null) {
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
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
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
                                expanded = searchExpanded,
                                onExpandedChange = { searchExpanded = it },
                                content = {},
                            )
                        }
                        Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Column {
                                TabRow(
                                    tabs = LogFilter.entries.map { stringResource(it.labelRes) },
                                    selectedTabIndex = LogFilter.entries.indexOf(filter),
                                    onTabSelected = { index -> filter = LogFilter.entries[index] },
                                )
                            }
                        }
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

            // 玻璃弹层挂在页面顶层（TopLayerHost 在 MainActivity），这样菜单不会被底栏和滚动内容裁掉。
            if (glassMenu) {
                TopLayer {
                    // GlassIconDropdownMenu 是 BoxScope 扩展，这里套一层满屏 Box 既拿作用域也保持坐标原点一致。
                    Box(modifier = Modifier.fillMaxSize()) {
                        GlassIconDropdownMenu(
                            show = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            anchor = moreAnchor,
                            anchorContent = {
                                Icon(
                                    imageVector = MiuixIcons.Glass.More,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                            },
                            entries = menuEntries,
                            backdrop = backdrop,
                            collapseOnSelection = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, onClick: () -> Unit) {
    BasicComponent(
        title = entry.level.name,
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

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> MiuixTheme.colorScheme.error
    LogLevel.WARN -> WarnColor
    LogLevel.INFO -> MiuixTheme.colorScheme.onSurface
    LogLevel.DEBUG -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}
