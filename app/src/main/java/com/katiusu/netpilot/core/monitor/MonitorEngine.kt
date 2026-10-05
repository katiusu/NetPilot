package com.katiusu.netpilot.core.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.katiusu.netpilot.core.NetPilotEvents
import com.katiusu.netpilot.core.priv.ControlManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 监控引擎：把「采样 → 判定 → 降级/恢复」这个循环固定在进程里的唯一入口。
 *
 * 状态对外一律用 StateFlow 暴露，界面直接 collect，不用自己轮询；循环本身
 * 挂在前台服务 [MonitorService] 上，服务活着循环就活着，服务被杀循环自动停，
 * 避免出现「界面显示在监控，实际早就不转了」这种假状态。
 */
object MonitorEngine {

    private const val TAG = "监控引擎"

    // IO 而不是 Default：循环里既做 HTTP 探测（Ping）又跑特权 shell，都是阻塞调用。
    // 之前用 Default 虽然不在主线程上、不会抛 NetworkOnMainThreadException，
    // 但会占住 CPU 线程池；统一放 IO，语义也跟调用内容对得上。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var engine: AutoDowngradeEngine? = null
    private var loop: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _snapshot = MutableStateFlow<SignalSnapshot?>(null)
    val snapshot: StateFlow<SignalSnapshot?> = _snapshot.asStateFlow()

    private val _judgement = MutableStateFlow<FakeJudgement?>(null)
    val judgement: StateFlow<FakeJudgement?> = _judgement.asStateFlow()

    private val _downgrade = MutableStateFlow(DowngradeState())
    val downgrade: StateFlow<DowngradeState> = _downgrade.asStateFlow()

    private val _lastTickAt = MutableStateFlow(0L)
    val lastTickAt: StateFlow<Long> = _lastTickAt.asStateFlow()

    private val _channelLabel = MutableStateFlow("未探测")
    val channelLabel: StateFlow<String> = _channelLabel.asStateFlow()

    fun init(context: Context) {
        if (engine == null) {
            engine = AutoDowngradeEngine(context.applicationContext)
            LogStore.info(TAG, "监控引擎已初始化")
        }
        publish(engine!!)
    }

    private fun ensure(context: Context): AutoDowngradeEngine {
        init(context)
        return engine!!
    }

    fun startLoop(context: Context, reason: String) {
        val e = ensure(context)
        if (loop?.isActive == true) return
        val ctx = context.applicationContext
        _running.value = true
        val interval = MonitorSettings.thresholds().monitorIntervalSec
        LogStore.info(TAG, "启动监控循环（$reason，采样间隔 ${interval}s）")
        loop = scope.launch {
            // 启动自愈：上次退出时如果还停在降级态，先无条件把完整制式写回去
            runCatching { e.selfHeal() }.onFailure {
                LogStore.warn(TAG, "启动自愈失败：${it.message ?: it.javaClass.simpleName}")
            }
            publish(e)
            // 连续「读数靠近门限」的轮数。0 = 用配置的间隔；每多靠近一轮就乘一次
            // thresholds.adaptiveStepFactor（默认 0.85，可在功能页调），任何一轮不靠近立刻归零。
            var nearStreak = 0
            while (isActive) {
                val t = MonitorSettings.thresholds()
                // 后台循环允许在「屏幕关闭 + 未降级 + 信号非强」时跳过 HTTP 探测；
                // 界面/Tasker 主动触发的 sampleOnce() 走 tick() 的默认值，永远真探。
                val snap = runCatching { e.tick(allowProbeSkip = true) }.getOrElse {
                    LogStore.error(TAG, "采样失败：${it.message ?: it.javaClass.simpleName}")
                    null
                }
                publish(e)
                // 采样结果向外播（数据卡规则等）：走事件钩子而不是直接 import，
                // 保持 monitor 与 datacard 两个包单向依赖。
                if (snap != null) {
                    runCatching { NetPilotEvents.onSample?.invoke(ctx, snap) }
                        .onSuccess { msg ->
                            if (msg != null) LogStore.info("数据卡规则", msg)
                        }
                        .onFailure {
                            LogStore.warn(
                                "数据卡规则",
                                "后处理异常：${it.message ?: it.javaClass.simpleName}",
                            )
                        }
                }
                // 自适应采样间隔：读数靠近门限时逐轮缩短，远离时立刻恢复。
                // 只有「这一轮真的量到了东西」才算靠近 —— 采样失败（snap == null）时
                // 没有任何读数可以判断远近，此时保持原间隔，而不是假装远离。
                val near = snap != null && t.adaptiveIntervalEnabled && snap.isNearThreshold(t)
                val wasNear = nearStreak > 0
                nearStreak = if (near) nearStreak + 1 else 0
                val intervalMs = adaptiveIntervalMs(t, nearStreak)
                // 只在「节奏发生变化」的那一轮记日志，靠近期间不会每轮都刷一条。
                if (near != wasNear) {
                    LogStore.debug(
                        TAG,
                        if (near) {
                            "读数靠近判定门限，采样间隔开始逐轮缩短（本轮 ${intervalMs / 1000}s）"
                        } else {
                            "读数已远离判定门限，采样间隔恢复为 ${intervalMs / 1000}s"
                        },
                    )
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * 把「连续靠近门限的轮数」换算成本轮实际等待的毫秒数。
     *
     * 每多靠近一轮就乘一次 [DowngradeThresholds.adaptiveStepFactor]（默认 0.85，可调），
     * 但**最多只缩到配置值的一半**（[MonitorSettings.ADAPTIVE_MIN_FACTOR]）。为什么必须有下限：判定阈值是
     * 固定的，采样再密也不会让读数更准，反而让 modem 查询与 HTTP 探测本身变成耗电源 ——
     * 自适应可以变快，但不能变成「一直快」。
     *
     * 仍然走原来的 `coerceIn(15, 3600)`：自适应既不能突破技术下限 15 秒，也不会把间隔
     * 放大到超过用户配置（[nearStreak] 为 0 时原样返回）。
     */
    private fun adaptiveIntervalMs(t: DowngradeThresholds, nearStreak: Int): Long {
        val base = t.monitorIntervalSec
        if (!t.adaptiveIntervalEnabled || nearStreak <= 0) {
            return base.coerceIn(15, 3600) * 1000L
        }
        // 倍率来自用户配置（1.4.0 起可调），下限仍用常量夹住：
        // 无论用户把倍率调多小，间隔都不会缩到配置值一半以下。
        val factor = Math.pow(
            t.adaptiveStepFactor.toDouble(),
            nearStreak.toDouble(),
        ).coerceAtLeast(MonitorSettings.ADAPTIVE_MIN_FACTOR.toDouble())
        return (base * factor).toInt().coerceIn(15, 3600) * 1000L
    }

    fun stopLoop(reason: String) {
        if (loop == null) return
        loop?.cancel()
        loop = null
        _running.value = false
        LogStore.info(TAG, "停止监控循环（$reason）")
    }

    private fun publish(e: AutoDowngradeEngine) {
        _snapshot.value = e.snapshot.value
        _judgement.value = e.judgement.value
        _downgrade.value = e.state.value
        _channelLabel.value = ControlManager.cachedLabel()
        _lastTickAt.value = System.currentTimeMillis()
    }

    /**
     * 立即采样一次（不依赖循环是否在跑），给界面「立即检测」、监控页 5 秒快采和 Tasker 用。
     *
     * **必须切到 IO**：界面调用点都在主线程上（Compose 的 LaunchedEffect / 组合作用域，
     * 或 BroadcastReceiver.onReceive），而 tick() 里会做 HTTP 探测和特权 shell。
     * 不切的话 HttpURLConnection 直接抛 NetworkOnMainThreadException —— 它没有 message，
     * 界面上只能看到一句「…失败：android.os.NetworkOnMainThreadException」，看不出所以然。
     */
    suspend fun sampleOnce(context: Context): SignalSnapshot = withContext(Dispatchers.IO) {
        val e = ensure(context)
        val snap = e.tick()
        publish(e)
        snap
    }

    /**
     * 总开关。刻意只走前台服务：循环的起停由服务生命周期决定，
     * 这样「APP 被划掉」「服务被杀」这些情况下的状态永远是一致的。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val ctx = context.applicationContext
        if (enabled) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            }.onFailure {
                LogStore.error(TAG, "启动前台服务失败：${it.message ?: it.javaClass.simpleName}")
            }
        } else {
            runCatching { ctx.stopService(Intent(ctx, MonitorService::class.java)) }
        }
    }

    /** 清空降级状态机（不动网络，只清留档）。 */
    fun resetState(context: Context) {
        val e = ensure(context)
        e.resetState()
        publish(e)
    }
}
