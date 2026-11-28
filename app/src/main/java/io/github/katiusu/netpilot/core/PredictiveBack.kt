package io.github.katiusu.netpilot.core

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import io.github.katiusu.netpilot.core.monitor.LogStore
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 预测性返回手势（predictive back）的应用级开关。
 *
 * 做法照抄 HyperLyric（com.lidesheng.hyperlyric/ui/utils/AppUtils.kt:10-30，实测可用）：
 * 真正的开关是 hidden API `ApplicationInfo#setEnableOnBackInvokedCallback(boolean)`，
 * 而且**必须在 Activity 创建之前**设好才生效 —— 系统在建 ActivityRecord 时就读取这个
 * 标志，之后再改只影响下一个新 Activity。所以入口只有 Application.onCreate 一处
 * （外加设置页切开关时立刻重设 + recreate 一次，见 MainActivity）。
 *
 * 为什么 2.0.1 那条路是死路：当时反射的是 `Activity#setEnableOnBackInvokedCallback`
 * 与 `Window#setEnableOnBackInvokedCallback`。Activity 上那个是 @SystemApi，普通应用
 * 调用必抛（被 runCatching 静默吞掉）；Window 上根本没有这个方法 —— 两个都失败，所以
 * 设置页的开关从来没起过作用。ApplicationInfo 上的这个才是 AppCompat / 系统自己用的入口。
 *
 * hidden API 豁免用工程里已有的 hiddenapibypass（TelephonyReflection 同款），不引新依赖。
 */
object PredictiveBack {

    private const val TAG = "PredictiveBack"

    private const val EXEMPTION =
        "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"

    /**
     * 按偏好把整应用的预测性返回设为开/关。
     *
     * 任何异常都只记一条日志：这个开关只影响返回动画，绝不能因为它让应用起不来。
     */
    fun install(application: Application, enabled: Boolean) {
        // 预测性返回从 API 33（T）开始有；HyperLyric 的实现在 34 上验证。
        // 本工程 minSdk 33，两个版本都走同一条路径。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(EXEMPTION)
            val method = ApplicationInfo::class.java.getDeclaredMethod(
                "setEnableOnBackInvokedCallback",
                java.lang.Boolean.TYPE,
            )
            method.isAccessible = true
            method.invoke(application.applicationInfo, enabled)
        }.onFailure {
            runCatching {
                LogStore.warn(TAG, "设置预测性返回失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }
}
