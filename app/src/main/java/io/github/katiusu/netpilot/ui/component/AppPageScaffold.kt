package io.github.katiusu.netpilot.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.ui.component.search.rememberAppSearchOpen
import io.github.katiusu.netpilot.ui.util.PageBackdrop
import io.github.katiusu.netpilot.ui.util.pageScrollModifiers
import io.github.katiusu.netpilot.ui.util.pageStartInset
import io.github.katiusu.netpilot.ui.util.rememberTopBarBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

val AppPageContentWindowInsets: WindowInsets
    @Composable
    get() = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)

@Composable
fun AppPageScaffold(
    title: String,
    isBlurEnabled: Boolean,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    onBack: (() -> Unit)? = null,
    titleAlpha: (() -> Float)? = null,
    expandable: Boolean = true,
    useGlassTopBar: Boolean = true,
    color: Color? = null,
    topBarSearchAction: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    contentWindowInsets: WindowInsets = AppPageContentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val topBarBackdrop = rememberTopBarBackdrop(isBlurEnabled)
    val blurActive = topBarBackdrop != null
    val barColor = color ?: if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val openSearch = rememberAppSearchOpen()
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                color = barColor,
                backdrop = topBarBackdrop,
                onBack = onBack,
                titleAlpha = titleAlpha ?: { 1f },
                expandable = expandable,
                useGlassTopBar = useGlassTopBar,
                actions = {
                    if (topBarSearchAction && openSearch != null) {
                        AppTopAppBarIconAction(onClick = openSearch) {
                            Icon(
                                imageVector = MiuixIcons.Glass.Search,
                                contentDescription = stringResource(R.string.search_hint),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    actions()
                },
            )
        },
        contentWindowInsets = contentWindowInsets,
    ) { innerPadding ->
        PageBackdrop(topBarBackdrop) {
            content(innerPadding)
        }
    }
}

@Composable
fun AppLazyPage(
    title: String,
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    onBack: (() -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit = {},
    topBarSearchAction: Boolean = false,
    contentWindowInsets: WindowInsets = AppPageContentWindowInsets,
    content: LazyListScope.() -> Unit,
) {
    AppPageScaffold(
        title = title,
        isBlurEnabled = isBlurEnabled,
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        onBack = onBack,
        actions = topBarActions,
        topBarSearchAction = topBarSearchAction,
        contentWindowInsets = contentWindowInsets,
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pageStartInset()
                .pageScrollModifiers(
                    showTopAppBar = true,
                    topAppBarScrollBehavior = scrollBehavior,
                    contentState = listState,
                ),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + extraBottomPadding,
            ),
            content = content,
        )
    }
}
