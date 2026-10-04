package com.katiusu.netpilot.util

import android.annotation.SuppressLint
import android.os.Build

/**
 * 系统版本检测工具（与 HyperNavBar 保持一致）。
 */
object SystemVersionDetector {

    /** 设备市场名称，如 `REDMI K90 Pro Max`；取不到时回退 [Build.MODEL]。 */
    fun getMarketName(): String =
        getProp("ro.product.marketname").ifEmpty { Build.MODEL }

    /** HyperOS / MIUI 版本增量号。 */
    fun getHyperOsVersion(): String =
        getProp("ro.mi.os.version.incremental").ifEmpty { getProp("ro.system.build.version.incremental") }

    /** Android 版本描述，如 `Android 15 (SDK 35)`。 */
    fun getAndroidVersion(): String =
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    private const val PROP_VENDOR_OS_NAME_MI = "ro.miui.ui.version.name"
    private const val PROP_VENDOR_OS_NAME_HYPER = "ro.mi.os.version.name"
    private const val PROP_BUILD_INCREMENTAL = "ro.build.version.incremental"

    /**
     * 当前真实系统版本，给设置页「OS 版本」一行用。
     *
     * 顺序（**前一个读不到才退到下一个，全程不猜、不写死**）：
     * 1. `ro.miui.ui.version.name` → `MIUI <x>`
     * 2. `ro.mi.os.version.name` → `HyperOS <x>`
     * 3. [getHyperOsVersion]（`ro.mi.os.version.incremental` → `ro.system.build.version.incremental`）
     * 4. `ro.build.version.incremental`（AOSP 构建增量号，非小米机型通常也有）
     * 5. [Build.VERSION.RELEASE]（最后的保底，至少能看出 Android 版本）
     *
     * 为什么留这么多兜底：这些属性名在各代 ROM 上并不统一，任何一个都可能为空；
     * 多试几个的成本只是一次反射调用，而「未知」对排障毫无帮助。
     *
     * 为什么设置页那一行改叫「OS 版本」：本应用不止跑在小米机型上，要展示的是
     * 「这台机器当前跑的是什么系统版本」，而不是「是不是小米」。非小米机型走到第 4/5 步，
     * 拿到的也是货真价实的值。
     */
    fun getOsVersion(): String {
        getProp(PROP_VENDOR_OS_NAME_MI).trim().takeIf { it.isNotEmpty() }?.let { return "MIUI $it" }
        getProp(PROP_VENDOR_OS_NAME_HYPER).trim().takeIf { it.isNotEmpty() }?.let { return "HyperOS $it" }
        getHyperOsVersion().trim().takeIf { it.isNotEmpty() }?.let { return it }
        getProp(PROP_BUILD_INCREMENTAL).trim().takeIf { it.isNotEmpty() }?.let { return it }
        return Build.VERSION.RELEASE.orEmpty()
    }

    @SuppressLint("PrivateApi")
    private fun getProp(property: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("get", String::class.java)
        method.invoke(null, property) as? String ?: ""
    } catch (_: Exception) {
        ""
    }
}
