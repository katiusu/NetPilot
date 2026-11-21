package io.github.katiusu.netpilot.ui.theme


import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.katiusu.netpilot.ui.util.isInDarkTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.ThemeController


@Composable
fun AppTheme(
    themeMode: ColorSchemeMode = ColorSchemeMode.System,
    content: @Composable () -> Unit
) {
    val controller = remember(themeMode) { ThemeController(themeMode) }
    MiuixTheme(
        controller = controller,
        content = {
            val isDark = isInDarkTheme()
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !isDark
                    insetsController.isAppearanceLightNavigationBars = !isDark
                }
            }
            // 全局打开 miuix 的连续曲率圆角：下拉弹层、级联弹层等库内自绘圆角
            // 走 squircle 而不是普通圆弧。
            CompositionLocalProvider(LocalSquircleEnabled provides true) {
                content()
            }
        }
    )
}
