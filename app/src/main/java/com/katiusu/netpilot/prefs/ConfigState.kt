package com.katiusu.netpilot.prefs

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import java.util.concurrent.ConcurrentHashMap

/**
 * 响应式配置状态。Compose 组件读取这里以获得自动重组。
 */
object ConfigState {

    private val values = mutableStateMapOf<String, Any?>()

    /** 配置写入后的回调。写入可能来自任何线程，故用并发容器。 */
    private val observers = ConcurrentHashMap<String, (Any?) -> Unit>()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        PrefsStore.init(context)
        if (!initialized) reload()
    }

    /** 从磁盘重新加载全部配置（导入后调用）。 */
    fun reload() {
        values.clear()
        PrefsStore.getAll().forEach { (key, value) -> values[key] = value }
        initialized = true
    }

    fun get(key: String): Any? = values[key]

    fun bool(key: String, defaultValue: Boolean): Boolean =
        values[key] as? Boolean ?: defaultValue

    fun int(key: String, defaultValue: Int): Int =
        (values[key] as? Number)?.toInt() ?: defaultValue

    fun float(key: String, defaultValue: Float): Float =
        (values[key] as? Number)?.toFloat() ?: defaultValue

    fun string(key: String, defaultValue: String): String =
        values[key] as? String ?: defaultValue

    fun set(key: String, value: Any?) {
        values[key] = value
        PrefsStore.put(key, value)
        observers[key]?.invoke(value)
    }

    /**
     * 注册「某个键被写入」时的回调。
     *
     * 用途是把配置同步到系统侧（例如 [com.katiusu.netpilot.core.tasker.TaskerGate] 要按开关
     * 启用/禁用 Tasker 组件）。放在这个漏斗上而不是放进某个界面，是因为写配置的路径不止
     * UI 一条：配置导入、Tasker 命令、服务总开关都会写 [ConfigState]，挂在漏斗上才不会漏。
     */
    fun observe(key: String, observer: (Any?) -> Unit) {
        observers[key] = observer
    }
}
