package io.github.katiusu.netpilot.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun AppSectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    topSpace: Dp = 0.dp,
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) SmallTitle(text = title)
        Card(
            modifier = Modifier.sectionCardPadding(top = topSpace),
            colors = colors,
            content = content,
        )
    }
}

internal fun Modifier.sectionCardPadding(top: Dp = 0.dp): Modifier =
    padding(start = 12.dp, end = 12.dp, top = top, bottom = 12.dp)
