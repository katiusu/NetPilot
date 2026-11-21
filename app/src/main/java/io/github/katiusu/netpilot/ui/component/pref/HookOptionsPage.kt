package io.github.katiusu.netpilot.ui.component.pref

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.katiusu.netpilot.prefs.ConfigState
import io.github.katiusu.netpilot.prefs.OptionRegistry
import io.github.katiusu.netpilot.prefs.OptionSpec
import io.github.katiusu.netpilot.prefs.OptionType
import io.github.katiusu.netpilot.ui.util.BlurredBar
import io.github.katiusu.netpilot.ui.util.PageBackdrop
import io.github.katiusu.netpilot.ui.util.uniformBlurBackdrop
import io.github.katiusu.netpilot.ui.util.pageScrollModifiers
import io.github.katiusu.netpilot.ui.util.rememberBlurBackdrop
import io.github.katiusu.netpilot.ui.component.AppTopAppBar
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

data class HookSection(
    val titleRes: Int,
    val specs: List<OptionSpec>,
    val titleEn: String = "",
)

data class HookSubPage(
    val titleRes: Int,
    val specs: List<OptionSpec>,
    val onOpen: () -> Unit,
    val subPages: List<HookSubPage> = emptyList(),
)

private sealed interface SearchTarget {
    data class Section(val section: HookSection) : SearchTarget
    data class SubPage(val subPage: HookSubPage, val path: List<Int>) : SearchTarget
}

private fun flattenSubPages(
    subPages: List<HookSubPage>,
    prefix: List<Int> = emptyList(),
): List<Pair<HookSubPage, List<Int>>> = subPages.flatMap { subPage ->
    val path = prefix + subPage.titleRes
    listOf(subPage to path) + flattenSubPages(subPage.subPages, path)
}

@Composable
fun hookSectionTitle(section: HookSection): String = buildString {
    append(stringResource(section.titleRes))
    if (section.titleEn.isNotBlank()) {
        append("（").append(section.titleEn).append("）")
    }
}

@Composable
fun HookOptionsPage(
    title: String,
    sections: List<HookSection>,
    subPages: List<HookSubPage> = emptyList(),
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
    onArrowClick: (OptionSpec) -> Unit = {},
    customActionPackages: List<String> = emptyList(),
    topBarActions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = isBlurEnabled && backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var pendingScrollIndex by remember { mutableStateOf<Int?>(null) }
    var showQuickActions by remember { mutableStateOf(false) }

    val flatSubPages = remember(subPages) { flattenSubPages(subPages) }

    val allSpecs = sections.flatMap { it.specs } + flatSubPages.flatMap { it.first.specs }
    val configActionPackages = allSpecs
        .filter { it.type == OptionType.PACKAGE_LIST }
        .flatMap { parsePackageList(ConfigState.string(it.key, it.defaultString)) }
    val quickActionPackages = remember(configActionPackages, customActionPackages) {
        (configActionPackages + customActionPackages).distinct()
    }

    val searchTargets = remember(sections, flatSubPages) {
        buildMap<String, SearchTarget> {
            sections.forEach { section ->
                section.specs.forEach { spec ->
                    put(spec.key, SearchTarget.Section(section))
                    spec.masterKey?.let { put(it, SearchTarget.Section(section)) }
                }
            }
            flatSubPages.forEach { (subPage, path) ->
                subPage.specs.forEach { spec ->
                    put(spec.key, SearchTarget.SubPage(subPage, path))
                    spec.masterKey?.let { put(it, SearchTarget.SubPage(subPage, path)) }
                }
            }
        }
    }

    LaunchedEffect(sections, flatSubPages) {
        OptionRegistry.registerAll(allSpecs)
    }

    LaunchedEffect(expanded, pendingScrollIndex) {
        val target = pendingScrollIndex
        if (!expanded && target != null) {
            listState.animateScrollToItem(target)
            pendingScrollIndex = null
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = if (isBlurEnabled) backdrop else null, blurEnabled = isBlurEnabled) {
                AppTopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        topBarActions?.invoke()
                        if (quickActionPackages.isNotEmpty()) {
                            IconButton(onClick = { showQuickActions = true }) {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    contentDescription = stringResource(R.string.quick_action_title),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier.uniformBlurBackdrop(if (isBlurEnabled) backdrop else null)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pageScrollModifiers(showTopAppBar = true, topAppBarScrollBehavior = scrollBehavior),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + extraBottomPadding,
                ),
            ) {
                item {
                    SearchBar(
                        inputField = {
                            InputField(
                                query = query,
                                onQueryChange = { query = it },
                                onSearch = { expanded = false },
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                label = stringResource(R.string.search_hint),
                            )
                        },
                        outsideEndAction = {
                            MiuixText(
                                text = stringResource(R.string.action_cancel),
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .clickable(
                                        interactionSource = null,
                                        indication = null,
                                    ) {
                                        expanded = false
                                        query = ""
                                    },
                            )
                        },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 8.dp),
                    ) {
                        val results = OptionRegistry.search(context, query)
                            .filter { searchTargets.containsKey(it.key) }
                            .distinctBy { spec ->
                                when (val target = searchTargets[spec.key]) {
                                    is SearchTarget.Section -> "s:${target.section.titleRes}"
                                    is SearchTarget.SubPage -> "p:${target.path.joinToString("/")}"
                                    null -> spec.key
                                }
                            }
                        if (results.isEmpty()) {
                            MiuixText(
                                text = stringResource(R.string.search_empty),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote2,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                results.forEach { spec ->
                                    val target = searchTargets[spec.key]
                                    BasicComponent(
                                        title = stringResource(spec.titleRes),
                                        summary = when (target) {
                                            is SearchTarget.Section -> hookSectionTitle(target.section)
                                            is SearchTarget.SubPage -> target.path.map { stringResource(it) }.joinToString(" / ")
                                            null -> null
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            when (target) {
                                                is SearchTarget.Section ->
                                                    pendingScrollIndex = sections.indexOf(target.section) + 1
                                                is SearchTarget.SubPage -> target.subPage.onOpen()
                                                null -> Unit
                                            }
                                            expanded = false
                                            query = ""
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (!expanded) {
                    sections.forEach { section ->
                        item(key = section.titleRes) {
                            HookSectionCard(section) {
                                section.specs.forEach { spec ->
                                    HookOptionView(
                                        spec = spec,
                                        onArrowClick = { onArrowClick(spec) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQuickActions) {
        QuickActionDialog(
            packages = quickActionPackages,
            onDismiss = { showQuickActions = false },
        )
    }
}

@Composable
fun HookSectionCard(
    section: HookSection,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SmallTitle(text = hookSectionTitle(section))
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            content()
        }
    }
}
