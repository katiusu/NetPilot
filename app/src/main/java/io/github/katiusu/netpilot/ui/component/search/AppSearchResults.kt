package io.github.katiusu.netpilot.ui.component.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.ui.component.appTopBarSafeInsets
import io.github.katiusu.netpilot.prefs.OptionRegistry
import io.github.katiusu.netpilot.prefs.OptionSpec
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.glass.GlassSearchOverlayDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

object AppSearchIndex {

    private val labels = mutableStateMapOf<String, List<Int>>()

    fun putAll(mapping: Map<String, List<Int>>) {
        if (mapping.isNotEmpty()) labels.putAll(mapping)
    }

    fun labelFor(key: String): List<Int>? = labels[key]
}

@Composable
fun AppSearchResults(
    query: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    searchTopPadding: () -> Dp = { 0.dp },
) {
    val context = LocalContext.current
    val results = remember(query) { OptionRegistry.search(context, query) }
    if (query.isBlank()) return
    val resultsTop = appTopBarSafeInsets().asPaddingValues().calculateTopPadding() +
        GlassSearchOverlayDefaults.BarHeight + GlassSearchOverlayDefaults.VerticalPadding * 2
    val hasResults = results.isNotEmpty()
    val padding = remember(resultsTop, searchTopPadding, hasResults) {
        val horizontal = if (hasResults) 12.dp else 28.dp
        val gap = if (hasResults) 4.dp else 12.dp
        val base = PaddingValues(horizontal = horizontal, vertical = 24.dp)
        object : PaddingValues by base {
            override fun calculateTopPadding() = resultsTop + gap + searchTopPadding()
        }
    }
    if (!hasResults) {
        MiuixText(
            text = stringResource(R.string.search_empty),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote2,
            modifier = modifier.padding(padding),
        )
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = padding,
    ) {
        item(key = "results") {
            Card {
                results.forEach { spec ->
                    SearchResultRow(spec = spec, onPick = onPick)
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(spec: OptionSpec, onPick: (String) -> Unit) {
    val summary = AppSearchIndex.labelFor(spec.key)
        ?.map { stringResource(it) }
        ?.joinToString(" / ")
        ?: spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) }
    BasicComponent(
        title = stringResource(spec.titleRes),
        summary = summary,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onPick(spec.key) },
    )
}
