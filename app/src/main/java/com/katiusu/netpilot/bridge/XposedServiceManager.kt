package com.katiusu.netpilot.bridge

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 原名 `XposedServiceManager`，现为**纯 GUI 工程的本机替代实现**。
 *
 * 原模板通过 LSPosed 的 `XposedServiceHelper` 绑定模块服务，向宿主申请作用域、
 * 读取远程偏好。剥离 Xposed 后框架侧已不存在，因此这里只保留与 GUI 完全一致的
 * 公开 API（属性名 / 函数签名逐一对齐），让所有界面调用点无需改动：
 *
 * - [isActivated] / [isRootAvailable] / [rootChecked] / [scope]：状态位，界面直接读取；
 * - [checkRoot]：后台线程探测 root，结果回主线程；
 * - [refreshScope]：作用域列表恒为空（无模块可申请时无作用域概念）；
 * - [ensureScope]：回调成功，让「申请作用域」类交互在无框架时也不报错。
 */
object XposedServiceManager {

    /** 模块是否已在框架中激活。无框架环境下恒为 false。 */
    var isActivated: Boolean by mutableStateOf(false)
        private set

    /** 设备是否具备 root。 */
    var isRootAvailable: Boolean by mutableStateOf(false)
        private set

    /** root 探测是否已经完成（用于区分「未知」与「无 root」）。 */
    var rootChecked: Boolean by mutableStateOf(false)
        private set

    /** 当前模块作用域。无框架环境下恒为空列表。 */
    var scope: List<String> by mutableStateOf(emptyList())
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 由 Application 调用一次。 */
    fun init() {
        checkRoot()
    }

    /** 后台探测 root，结果回主线程刷新状态（不阻塞 UI）。 */
    fun checkRoot() {
        Thread {
            val available = detectRoot()
            mainHandler.post {
                isRootAvailable = available
                rootChecked = true
            }
        }.apply { isDaemon = true }.start()
    }

    /** 无框架环境下没有可查询的作用域，保持空列表。 */
    fun refreshScope() {
        scope = emptyList()
    }

    fun isInScope(packageName: String): Boolean = scope.contains(packageName)

    /**
     * 申请作用域。无框架时直接回调成功，保证调用方流程不变。
     */
    fun ensureScope(packages: List<String>, onResult: ((Boolean, String?) -> Unit)? = null) {
        onResult?.invoke(true, null)
    }

    fun removeScope(packages: List<String>) = Unit

    private fun detectRoot(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.contains("uid=0")
    }.getOrDefault(false)
}
