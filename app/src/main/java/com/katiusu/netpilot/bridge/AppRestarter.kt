package com.katiusu.netpilot.bridge

import android.content.Context
import android.content.Intent

/**
 * 应用重启 / 系统重启工具。
 *
 * - 普通应用：仅当应用在运行时，Root 下 `am force-stop` 后拉起启动 Activity；未运行则不更改、不打开。
 * - 系统界面（`com.android.systemui`）：结束进程由系统自动拉起（`restartSystemUi`）。
 * - system_server（`system` / `android`）：只能通过重启系统恢复，执行 `reboot`。
 */
object AppRestarter {

    private val SYSTEM_PACKAGES = setOf("system", "android", "system_server")

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    fun isSystemPackage(packageName: String): Boolean = packageName in SYSTEM_PACKAGES

    /**
     * 重启指定应用：应用在运行时 force-stop 后重新拉起；未运行则不更改、不打开。
     *
     * @return true 表示操作已完成或无需操作；false 表示缺少 Root 权限。
     */
    fun restart(context: Context, packageName: String): Boolean {
        if (!XposedServiceManager.isRootAvailable) return false
        if (packageName == SYSTEM_UI_PACKAGE) return restartSystemUi()
        if (isSystemPackage(packageName)) {
            return rootExec("reboot")
        }
        if (!isRunning(packageName)) return true
        val stopped = rootExec("am force-stop $packageName")
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
        }
        return stopped
    }

    /** 应用是否在运行（Root 下 `pidof` 匹配主进程）。 */
    private fun isRunning(packageName: String): Boolean = rootExec("pidof $packageName")

    /** 重启系统（用于 system_server 目标）。 */
    @Suppress("unused")
    fun reboot(): Boolean =
        XposedServiceManager.isRootAvailable && rootExec("reboot")

    /**
     * 重启系统界面（`com.android.systemui`）。
     *
     * 结束 SystemUI 进程触发系统自动重新拉起；`force-stop` 仅作兜底
     * （部分机型 force-stop 后不会自动恢复，故放在最后）。
     *
     * @return true 表示已执行重启命令；false 表示缺少 Root 权限。
     */
    fun restartSystemUi(processName: String = SYSTEM_UI_PACKAGE): Boolean {
        if (!XposedServiceManager.isRootAvailable) return false
        if (rootExec("pkill -f $processName")) return true
        if (rootExec("killall $processName")) return true
        return rootExec("am force-stop $processName")
    }

    /**
     * 以 root 执行一条命令（原 `RootHelper` 内联至此，剥离 Xposed 后 [RootHelper] 已移除）。
     *
     * @return true 表示命令以退出码 0 结束。
     */
    private fun rootExec(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0
    }.getOrDefault(false)
}
