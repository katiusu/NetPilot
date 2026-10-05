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

    /** Android 版本描述，如 `Android 15 (SDK 35)`。 */
    fun getAndroidVersion(): String =
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    private const val PROP_BUILD_INCREMENTAL = "ro.build.version.incremental"

    /**
     * 系统构建版本，给设置页「系统构建版本」一行用。
     *
     * 只认 `ro.build.version.incremental` 这一个属性；读不到则退到公开 API
     * [Build.VERSION.INCREMENTAL]（它与该属性是同一份值，等于同一件事的另一个入口）。
     *
     * 为什么不再按厂商 OS 名猜（原来的顺序里试过 `ro.miui.ui.version.name` /
     * `ro.mi.os.version.name`）：这一行现在叫「系统构建版本」，回答的是「这台机器当前是哪个
     * 构建号」；一旦拿厂商 OS 名顶上，展示的就是另一个东西了。厂商与品牌的差异由设置页的
     * 「厂商」「品牌」两行分别回答，不该混进构建号里。
     */
    fun getSystemBuild(): String =
        getProp(PROP_BUILD_INCREMENTAL).trim().ifEmpty { Build.VERSION.INCREMENTAL.orEmpty() }

    @SuppressLint("PrivateApi")
    private fun getProp(property: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("get", String::class.java)
        method.invoke(null, property) as? String ?: ""
    } catch (_: Exception) {
        ""
    }
}
