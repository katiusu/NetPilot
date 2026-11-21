package io.github.katiusu.netpilot.prefs

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

    /**
     * 从磁盘重新加载全部配置（导入后调用）。
     *
     * 为什么必须回调 observers：导入换掉的是磁盘上的值，而系统侧状态（Tasker 组件启停等）
     * 只认 [observe] 的回调。不回调的后果是「配置变了、系统侧还是旧的」，而且没有自愈路径，
     * 要等用户下一次手动动同一个开关才能对齐。
     *
     * 只回调**值确实变了**的键：导入一份只改了一条规则的备份，不该把所有联动都跑一遍。
     * 首次加载（[init] 阶段）直接返回：那时没有旧值可比，回调只会把冷启动拖慢。
     */
    fun reload() {
        val previous = HashMap(values)
        val firstLoad = !initialized
        values.clear()
        PrefsStore.getAll().forEach { (key, value) -> values[key] = value }
        initialized = true
        if (firstLoad) return
        // 先算出要回调哪些 key、再统一回调：observer 里可能又调 set()/observe()，
        // 一边遍历 observers 一边注册会撞上并发容器的遍历语义。
        val changedKeys = observers.keys.filter { previous[it] != values[it] }
        changedKeys.forEach { key ->
            val observer = observers[key] ?: return@forEach
            // 回调抛异常不能让导入失败（值已经落盘了），也不该中断其余回调。
            runCatching { observer(values[key]) }
        }
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
     * 用途是把配置同步到系统侧（例如 [io.github.katiusu.netpilot.core.tasker.TaskerGate] 要按开关
     * 启用/禁用 Tasker 组件）。放在这个漏斗上而不是放进某个界面，是因为写配置的路径不止
     * UI 一条：配置导入、Tasker 命令、服务总开关都会写 [ConfigState]，挂在漏斗上才不会漏。
     */
    fun observe(key: String, observer: (Any?) -> Unit) {
        observers[key] = observer
    }
}
