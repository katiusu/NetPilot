package com.katiusu.netpilot.core

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.katiusu.netpilot.R
import com.katiusu.netpilot.prefs.PrefsStore

/**
 * 权限与首启引导的**纯逻辑**部分：该申请哪些权限、该弹哪个框、怎么跳系统设置。
 *
 * 刻意不塞进 MainActivity（它已经接近 500 行），Compose 侧只负责把这里算出来的结果画出来，
 * 见 [com.katiusu.netpilot.ui.component.PermissionDialogs]。
 *
 * 标记统一写 [PrefsStore] 而不是 ConfigState：它们只是「引导是否展示过」的运行期一次性标记，
 * 不是用户可配置项；放进 ConfigState 会让配置导入 / 导出平白多出这些无意义的键。
 */
object PermissionGuide {

    /** MIUI / HyperOS 的自启动管理页。 */
    private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
    private const val MIUI_AUTOSTART_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"

    /** 是否已展示过「权限理由说明」，避免每次冷启动都弹一次。 */
    const val KEY_RATIONALE_SHOWN = "perm_rationale_shown"

    /**
     * 是否已展示过「权限被拒说明」。
     *
     * 用户点「稍后」说明已经知晓，不必下次冷启动再打扰；权限齐全时会被清回 false，
     * 这样以后真的又一次被拒还能再提示一次。
     */
    const val KEY_DENIED_PROMPTED = "perm_denied_prompted"

    /** 是否已展示过首启的「自启动 + 省电策略」引导，只弹一次。 */
    const val KEY_FIRST_LAUNCH_GUIDE_SHOWN = "perm_first_launch_guide_shown"

    /**
     * 一条「权限 + 为什么需要」。
     *
     * [reasonRes] 必须写成用户能看懂的功能损失（缺了会怎么样），不能只写「为了正常运行」——
     * 这是用户原始反馈里明确要求的。
     */
    data class Item(
        val permission: String,
        @StringRes val nameRes: Int,
        @StringRes val reasonRes: Int,
    )

    /**
     * 申请顺序 = 对话框里的展示顺序。
     *
     * - READ_PHONE_STATE：读信号强度 / 网络制式 / 双卡 subId
     * - ACCESS_FINE_LOCATION：Android 10+ 把蜂窝信号强度（含 SINR）归类为位置相关数据
     * - POST_NOTIFICATIONS：监控是前台服务，Android 13+ 缺这条通知权限服务无法常驻
     */
    val ITEMS: List<Item> = listOf(
        Item(
            permission = Manifest.permission.READ_PHONE_STATE,
            nameRes = R.string.perm_item_phone_state_name,
            reasonRes = R.string.perm_item_phone_state_reason,
        ),
        Item(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            nameRes = R.string.perm_item_location_name,
            reasonRes = R.string.perm_item_location_reason,
        ),
        Item(
            permission = Manifest.permission.POST_NOTIFICATIONS,
            nameRes = R.string.perm_item_notification_name,
            reasonRes = R.string.perm_item_notification_reason,
        ),
    )

    /**
     * 当前系统版本上真实存在的权限项。
     *
     * POST_NOTIFICATIONS 是 Android 13 才引入的：在 12 及以下 `checkSelfPermission` 对它
     * 一律返回 DENIED，如果不按版本过滤，就会在旧机上永远显示「通知权限未授予」并反复引导。
     */
    fun requiredItems(): List<Item> = ITEMS.filter { existsOnThisDevice(it.permission) }

    /** 尚未授予的权限项（按 [ITEMS] 顺序）。 */
    fun missingItems(context: Context): List<Item> =
        requiredItems().filter { !isGranted(context, it.permission) }

    /** 直接喂给 `RequestMultiplePermissions` 的权限名数组。 */
    fun missingPermissionNames(context: Context): Array<String> =
        missingItems(context).map { it.permission }.toTypedArray()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context): Boolean = missingItems(context).isEmpty()

    /** 冷启动时该弹哪个引导框。 */
    enum class StartupPrompt { NONE, RATIONALE, DENIED }

    /**
     * 启动引导的判定顺序：
     * 1. 权限齐全 → 什么都不弹（用户明确要求），顺手把「被拒已提示」清掉；
     * 2. 还没看过理由说明 → 弹理由说明；
     * 3. 看过理由说明但权限仍缺 → 给一次「去设置」的机会，用户忽略过就不再打扰。
     */
    fun startupPrompt(context: Context): StartupPrompt {
        if (allGranted(context)) {
            if (PrefsStore.getBoolean(KEY_DENIED_PROMPTED, false)) {
                PrefsStore.put(KEY_DENIED_PROMPTED, false)
            }
            return StartupPrompt.NONE
        }
        if (!PrefsStore.getBoolean(KEY_RATIONALE_SHOWN, false)) return StartupPrompt.RATIONALE
        if (!PrefsStore.getBoolean(KEY_DENIED_PROMPTED, false)) return StartupPrompt.DENIED
        return StartupPrompt.NONE
    }

    /** 理由说明已弹过（用户点了「继续授权」，接下来会走系统权限框）。 */
    fun markRationaleShown() {
        PrefsStore.put(KEY_RATIONALE_SHOWN, true)
    }

    /**
     * 用户在理由说明里点了「取消」。
     *
     * 既然连系统权限框都没调起，就不存在「被拒」这回事，所以连被拒提示一起标记掉，
     * 免得下次冷启动弹一个说「权限被拒绝」的框。
     */
    fun skipPermissionGuide() {
        PrefsStore.put(KEY_RATIONALE_SHOWN, true)
        markDeniedPrompted()
    }

    /** 被拒说明已展示过。 */
    fun markDeniedPrompted() {
        PrefsStore.put(KEY_DENIED_PROMPTED, true)
    }

    fun shouldShowFirstLaunchGuide(): Boolean =
        !PrefsStore.getBoolean(KEY_FIRST_LAUNCH_GUIDE_SHOWN, false)

    fun markFirstLaunchGuideShown() {
        PrefsStore.put(KEY_FIRST_LAUNCH_GUIDE_SHOWN, true)
    }

    /**
     * 打开 MIUI / HyperOS 的自启动管理页。
     *
     * `resolveActivity` 用来判断这台机器有没有该页面；但 Android 11+ 的软件包可见性会让它
     * 对未在 `<queries>` 里声明的第三方包返回 null，所以我们只把「解析不到」当作弱信号：
     * 小米系 ROM 上即使解析不到也照样试一次，真正的可用性以 `startActivity` 是否抛异常为准。
     */
    fun openAutostartSettings(context: Context): Boolean = runCatching {
        val intent = Intent().setComponent(ComponentName(MIUI_SECURITY_CENTER, MIUI_AUTOSTART_ACTIVITY))
        val resolvable = intent.resolveActivity(context.packageManager) != null
        if (!resolvable && !isMiuiFamily()) return@runCatching false
        context.startSafely(intent)
    }.getOrDefault(false)

    /** 打开本应用的「应用程式详情」页。 */
    fun openAppDetails(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startSafely(intent)
    }.getOrDefault(false)

    /**
     * 「去设置」按钮的跳转链：先试自启动管理页，机型不支持再回落到应用程式详情页。
     *
     * 两个页面都在系统设置里，任何一步失败都只是返回 false，不会崩。
     */
    fun openStartupSettings(context: Context): Boolean =
        openAutostartSettings(context) || openAppDetails(context)

    /**
     * 申请「忽略电池优化」（省电策略无限制）。
     *
     * 该 action 在部分 ROM 上被裁掉，失败就回落到应用详情页，让用户自己去改省电策略。
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (context.startSafely(intent)) true else openAppDetails(context)
    }.getOrDefault(false)

    /** 当前是否已经「忽略电池优化」，用来决定还要不要显示申请入口。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }.getOrDefault(false)

    private fun existsOnThisDevice(permission: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            permission != Manifest.permission.POST_NOTIFICATIONS

    /** 小米系 ROM 判定：只有这些机型才可能预装 com.miui.securitycenter。 */
    private fun isMiuiFamily(): Boolean = runCatching {
        val brand = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase()
        brand.contains("xiaomi") || brand.contains("redmi") ||
            brand.contains("poco") || brand.contains("mi ")
    }.getOrDefault(false)

    /**
     * 起 Activity 的统一入口：非 Activity 上下文（Application / Service）必须带 NEW_TASK，
     * 否则抛 AndroidRuntimeException；所有跳转都包在 runCatching 里，任何机型上失败都不能崩。
     */
    private fun Context.startSafely(intent: Intent): Boolean = runCatching {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    }.getOrDefault(false)
}
