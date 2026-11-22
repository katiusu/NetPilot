package io.github.katiusu.netpilot

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * 底部导航栏形态。
 *
 * 为什么把原来的「悬浮栏 + 液态玻璃」两个布尔换成一个枚举：那两个开关能组合出本不该存在的
 * 组合（非悬浮却是液态玻璃），UI 与状态对不上；收敛成单值后只有两档可选。
 */
enum class BottomBarStyle(val value: Int) {
    HyperOS(1),
    IOS(2);

    companion object {
        fun fromValue(value: Int): BottomBarStyle = entries.firstOrNull { it.value == value } ?: HyperOS
    }
}

/**
 * 宽屏下的侧栏形态。
 *
 * 为什么 None 也是一档：折叠式（Top）与常驻展开式（Left）是两种不同的使用习惯，
 * 而「不要侧栏、继续用底部栏」同样是有效诉求；如果合并成「没有侧栏」的布尔，
 * 三态判断就会散落到各个调用点。
 */
enum class RailStyle(val value: Int) {
    None(0),
    Top(1),
    Left(2);

    val isEnabled: Boolean get() = this != None

    companion object {
        val Default = Left

        fun fromValue(value: Int): RailStyle = entries.firstOrNull { it.value == value } ?: Default
    }
}

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
    val bottomBarStyle: BottomBarStyle = BottomBarStyle.HyperOS,
    val railStyle: RailStyle = RailStyle.Default,
    /**
     * 预测性返回（Android 13+ 的返回手势预览动画）。
     *
     * 默认开着：它是系统默认行为，偏好只是给「某些机型上返回动画卡顿」留一个出口；
     * 关掉后返回手势仍然工作，只是没有预览动画。
     */
    val predictiveBack: Boolean = true,
    val isBlurEnabled: Boolean = true,
    val checkUpdateOnLaunch: Boolean = true,
    val language: String = "",
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("themeMode", themeMode)
        json.put("bottomBarStyle", bottomBarStyle.value)
        json.put("railStyle", railStyle.value)
        json.put("predictiveBack", predictiveBack)
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
                    bottomBarStyle = BottomBarStyle.fromValue(
                        obj.optInt("bottomBarStyle", BottomBarStyle.HyperOS.value)
                    ),
                    railStyle = RailStyle.fromValue(
                        obj.optInt("railStyle", RailStyle.Default.value)
                    ),
                    predictiveBack = obj.optBoolean("predictiveBack", true),
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
        private const val KEY_BOTTOM_BAR_STYLE = "bottom_bar_style"
        private const val KEY_RAIL_STYLE = "rail_style"
        private const val KEY_PREDICTIVE_BACK = "predictive_back"
        private const val KEY_BLUR_ENABLED = "blur_enabled"
        private const val KEY_CHECK_UPDATE_ON_LAUNCH = "check_update_on_launch"

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val language = LocaleHelper.getSavedLanguage(context).code
            return AppSettings(
                themeMode = prefs.getString(KEY_THEME_MODE, "System") ?: "System",
                bottomBarStyle = BottomBarStyle.fromValue(
                    prefs.getInt(KEY_BOTTOM_BAR_STYLE, BottomBarStyle.HyperOS.value)
                ),
                railStyle = RailStyle.fromValue(
                    prefs.getInt(KEY_RAIL_STYLE, RailStyle.Default.value)
                ),
                predictiveBack = prefs.getBoolean(KEY_PREDICTIVE_BACK, true),
                isBlurEnabled = prefs.getBoolean(KEY_BLUR_ENABLED, true),
                checkUpdateOnLaunch = prefs.getBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, true),
                language = language,
            )
        }

        fun save(context: Context, settings: AppSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_THEME_MODE, settings.themeMode)
                putInt(KEY_BOTTOM_BAR_STYLE, settings.bottomBarStyle.value)
                putInt(KEY_RAIL_STYLE, settings.railStyle.value)
                putBoolean(KEY_PREDICTIVE_BACK, settings.predictiveBack)
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
