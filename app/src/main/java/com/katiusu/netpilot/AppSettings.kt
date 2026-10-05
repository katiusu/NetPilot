package com.katiusu.netpilot

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * 应用级偏好。
 *
 * 为什么这里不再有 `hideLauncherIcon`（1.5.0 删除）：那个偏好只用来禁用 manifest 里的
 * `activity-alias`，而 `MainActivity` 自己也带 MAIN/LAUNCHER 过滤器，桌面上始终有两个图标 ——
 * 它既藏不住图标，也从来没有接到任何 UI 开关上。现在别名整块删掉，偏好与相关代码一并清掉。
 * 旧的导出 JSON 里若还带这个键，[fromJson] 会直接忽略，导入不受影响。
 */
data class AppSettings(
    val themeMode: String = "System",
    val isFloatingNavbar: Boolean = false,
    val isLiquidGlass: Boolean = false,
    val isBlurEnabled: Boolean = true,
    val checkUpdateOnLaunch: Boolean = true,
    val language: String = "",
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("themeMode", themeMode)
        json.put("isFloatingNavbar", isFloatingNavbar)
        json.put("isLiquidGlass", isLiquidGlass)
        json.put("isBlurEnabled", isBlurEnabled)
        json.put("checkUpdateOnLaunch", checkUpdateOnLaunch)
        json.put("language", language)
        return json.toString(2)
    }

    companion object {
        fun fromJson(json: String): AppSettings {
            return try {
                val obj = JSONObject(json)
                AppSettings(
                    themeMode = obj.optString("themeMode", "System"),
                    isFloatingNavbar = obj.optBoolean("isFloatingNavbar", false),
                    isLiquidGlass = obj.optBoolean("isLiquidGlass", false),
                    isBlurEnabled = obj.optBoolean("isBlurEnabled", true),
                    checkUpdateOnLaunch = obj.optBoolean("checkUpdateOnLaunch", true),
                    language = obj.optString("language", ""),
                )
            } catch (_: Exception) {
                AppSettings()
            }
        }

        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FLOATING_NAVBAR = "floating_navbar"
        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_BLUR_ENABLED = "blur_enabled"
        private const val KEY_CHECK_UPDATE_ON_LAUNCH = "check_update_on_launch"

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val language = LocaleHelper.getSavedLanguage(context).code
            return AppSettings(
                themeMode = prefs.getString(KEY_THEME_MODE, "System") ?: "System",
                isFloatingNavbar = prefs.getBoolean(KEY_FLOATING_NAVBAR, false),
                isLiquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, false),
                isBlurEnabled = prefs.getBoolean(KEY_BLUR_ENABLED, true),
                checkUpdateOnLaunch = prefs.getBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, true),
                language = language,
            )
        }

        fun save(context: Context, settings: AppSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_THEME_MODE, settings.themeMode)
                putBoolean(KEY_FLOATING_NAVBAR, settings.isFloatingNavbar)
                putBoolean(KEY_LIQUID_GLASS, settings.isLiquidGlass)
                putBoolean(KEY_BLUR_ENABLED, settings.isBlurEnabled)
                putBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, settings.checkUpdateOnLaunch)
            }
            // Restore language
            val lang = LocaleHelper.Language.entries.find { it.code == settings.language } ?: LocaleHelper.Language.SYSTEM
            LocaleHelper.setLanguage(context, lang)
        }

        fun importFromJson(context: Context, json: String): AppSettings {
            val settings = fromJson(json)
            save(context, settings)
            return settings
        }
    }
}
