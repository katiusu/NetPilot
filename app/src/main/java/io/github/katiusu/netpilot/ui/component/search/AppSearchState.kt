package io.github.katiusu.netpilot.ui.component.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.ProvidableCompositionLocal

@Stable
class AppSearchState {

    var expanded by mutableStateOf(false)
        private set

    fun open() {
        expanded = true
    }

    fun close() {
        expanded = false
    }
}

val LocalAppSearchState: ProvidableCompositionLocal<AppSearchState?> = staticCompositionLocalOf { null }

@Composable
fun rememberAppSearchOpen(): (() -> Unit)? {
    val state = LocalAppSearchState.current ?: return null
    return state::open
}
