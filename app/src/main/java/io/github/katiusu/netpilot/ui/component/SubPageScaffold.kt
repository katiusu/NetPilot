package io.github.katiusu.netpilot.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import io.github.katiusu.netpilot.ui.util.LocalSubPageScrollBehavior
import io.github.katiusu.netpilot.ui.util.LocalSubPageScrollGate
import io.github.katiusu.netpilot.ui.util.SubPageScrollGate
import io.github.katiusu.netpilot.ui.util.withPageStartInset
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior

@Composable
fun SubPageScaffold(
    title: String,
    isBlurEnabled: Boolean,
    onBack: () -> Unit,
    topBarActions: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollGate = remember { SubPageScrollGate() }
    val scrollBehavior = MiuixScrollBehavior(canScroll = { scrollGate.contentScrollable })

    AppPageScaffold(
        title = title,
        isBlurEnabled = isBlurEnabled,
        scrollBehavior = scrollBehavior,
        onBack = onBack,
        actions = {
            topBarActions?.invoke()
        },
    ) { innerPadding ->
        val direction = LocalLayoutDirection.current
        val systemBottom = WindowInsets.navigationBars.union(WindowInsets.captionBar)
            .asPaddingValues().calculateBottomPadding()
        CompositionLocalProvider(
            LocalSubPageScrollBehavior provides scrollBehavior,
            LocalSubPageScrollGate provides scrollGate,
        ) {
            content(
                PaddingValues(
                    start = innerPadding.calculateStartPadding(direction),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(direction),
                    bottom = maxOf(innerPadding.calculateBottomPadding(), systemBottom),
                ).withPageStartInset(),
            )
        }
    }
}
