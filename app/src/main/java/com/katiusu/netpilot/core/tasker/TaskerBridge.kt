package com.katiusu.netpilot.core.tasker

import android.content.Context
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.monitor.SignalSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 把「监控引擎的采样 / 降级状态」接到「Tasker 事件广播」的接线器。
 *
 * ## 为什么订阅 StateFlow，而不是包裹 `NetPilotEvents.onSample`
 *
 * `NetPilotEvents.onSample` 是**单槽**钩子，`NetPilot.install()` 已经把它和
 * `onMonitorStateChanged` 各赋了一次值（给 `DataCardEngine` 与日志）。要复用这个槽，
 * 只能「先存旧 lambda，再赋一个先调旧的、后发事件的新 lambda」——多一层包装就多一处
 * 出错点，而且一旦以后有第二个模块也用同样的方法包装，包装链的顺序就变得不可预测。
 *
 * 更好的做法是**只读地订阅** [MonitorEngine] 对外暴露的 StateFlow
 * （`snapshot` / `downgrade`），既完全不碰别人的钩子，也能天然拿到降级状态的
 * 「变化沿」：StateFlow 会立刻回放当前值，之后每次变化都会推送，
 * 不需要自己再拿 SharedPreferences 记「上次是不是降级」。
 *
 * ## 幂等
 *
 * [init] 只允许真正启动一次（内部 volatile 标记 + 同步块），接收器每次执行命令时
 * 都可以放心调用它：进程可能刚被广播冷启动，此时订阅还没建立，补一次即可。
 */
object TaskerBridge {

    private const val TAG = "Tasker接线"

    private val lock = Any()

    /** 接线后的常驻协程作用域：随进程存活，进程被杀自然结束。 */
    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Volatile
    private var started = false

    /**
     * 启动事件发送。幂等：重复调用不会重复订阅（否则每次命令都会多发一遍事件）。
     *
     * 建议在 `TemplateApp.onCreate()` 里 **`NetPilot.install(this)` 之后**调用一次，
     * 保证第一次采样就能发出 `SIGNAL_SAMPLED`；接收器/编辑界面里也会各补调一次。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (started) return
            started = true
            startSnapshotCollector(app)
            startDowngradeCollector(app)
            LogStore.info(TAG, "Tasker 事件出口已接线（订阅 MonitorEngine 的 StateFlow）")
        }
    }

    /** 每次采样（`MonitorEngine.snapshot` 变化）发一条 SIGNAL_SAMPLED。 */
    private fun startSnapshotCollector(app: Context) {
        scope.launch {
            runCatching {
                MonitorEngine.snapshot.collectLatest { snapshot ->
                    if (snapshot != null) TaskerEventSender.signalSampled(app, snapshot)
                }
            }.onFailure { t ->
                LogStore.warn(TAG, "采样事件订阅中断：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 降级状态变化沿 → DOWNGRADED / RECOVERED。
     *
     * 首帧只用来记基线（StateFlow 一定会先把当前值回放一次，那不是「刚刚降级」），
     * 只有真正发生 false→true / true→false 的跳变才发事件。
     */
    private fun startDowngradeCollector(app: Context) {
        scope.launch {
            runCatching {
                var initialized = false
                var wasActive = false
                MonitorEngine.downgrade.collectLatest { state ->
                    val active = state.active
                    if (!initialized) {
                        initialized = true
                        wasActive = active
                    } else if (active != wasActive) {
                        wasActive = active
                        if (active) {
                            TaskerEventSender.downgraded(app, snapshotOrNull())
                        } else {
                            TaskerEventSender.recovered(app)
                        }
                    }
                }
            }.onFailure { t ->
                LogStore.warn(TAG, "降级事件订阅中断：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 主动上报制式变化。
     *
     * 制式没有 StateFlow（只有采样与降级态有），所以由**切换成功的调用方**调用；
     * [TaskerCommandExecutor] 在切制式/按预设成功后已经接上了这里。
     */
    fun notifyModeChanged(context: Context, subId: Int, modeValue: Int) {
        TaskerEventSender.modeChanged(context.applicationContext, subId, modeValue)
    }

    /** 主动上报默认数据卡变化（切换默认卡 / 双卡互换成功后由调用方触发）。 */
    fun notifyDataSimChanged(context: Context, subId: Int) {
        TaskerEventSender.dataSimChanged(context.applicationContext, subId)
    }

    /** 读当前快照，全程不抛异常。 */
    private fun snapshotOrNull(): SignalSnapshot? =
        runCatching { NetPilot.snapshot() }.getOrNull()
}
