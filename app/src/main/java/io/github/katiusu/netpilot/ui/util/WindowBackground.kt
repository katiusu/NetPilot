package io.github.katiusu.netpilot.ui.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable

fun Activity.applyWindowBackground(themeModeName: String) {
    val dark = when (themeModeName) {
        "Dark", "MonetDark" -> true
        "Light", "MonetLight" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    val color = if (dark) 0xFF000000.toInt() else 0xFFF7F7F7.toInt()
    window.setBackgroundDrawable(ColorDrawable(color))
}
