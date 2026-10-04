package com.katiusu.netpilot

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 控制桌面图标的显示与隐藏。
 *
 * 桌面图标由 manifest 中的 [LAUNCHER_ALIAS_CLASS] 别名提供，
 * [apply] 通过启停该别名组件实现「隐藏 / 显示桌面图标」，不触碰 MainActivity 本身。
 */
object LauncherIconController {

    private const val LAUNCHER_ALIAS_CLASS = "com.katiusu.netpilot.LauncherAlias"

    fun apply(context: Context, hide: Boolean) {
        val component = ComponentName(context.packageName, LAUNCHER_ALIAS_CLASS)
        val state = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
