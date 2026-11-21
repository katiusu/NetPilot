package io.github.katiusu.netpilot.core.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import io.github.katiusu.netpilot.core.ServicesGate
import io.github.katiusu.netpilot.core.monitor.LogStore

/**
 * 屏幕亮/灭的观察者：唯一职责是「屏幕状态一变，就按新状态把保活闹钟重排一次」。
 *
 * 为什么需要它（1.5.4）：保活心跳是 `ELAPSED_REALTIME_WAKEUP` —— 它会把 CPU 从挂起里叫醒。
 * 用户的要求是「锁屏时才停止定时唤醒」，而**闹钟类型只能是排闹钟那一刻决定的**，没有 API 能让
 * 已排好的闹钟改成不唤醒 ⇒ 只能在屏幕状态变化的当时重排一次（重排逻辑在 [KeepAliveScheduler]，
 * 它自己会读屏幕状态选类型，本类不复制那套判断）。
 *
 * 为什么是动态注册而不是 Manifest：`ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` 是系统保护广播，
 * Android 8.0 起 Manifest 声明收不到（声明了也静默失效）。用 `RECEIVER_NOT_EXPORTED`
 * （minSdk 34 必需）：这两个广播只有系统能发。
 *
 * 依赖方向：本类 -> KeepAliveScheduler / ServicesGate / LogStore；KeepAliveScheduler 只调用本类
 * 的 [isScreenOn] 这个纯读方法，没有反向依赖，不会成环。
 */
object ScreenStateGate {

    private const val TAG = "屏幕状态"

    @Volatile
    private var registered = false

    /**
     * 两次重排之间的最小间隔。
     *
     * [KeepAliveScheduler.schedule] 用的是 `setInexactRepeating`，**每次调用都会把下一次触发
     * 重置成「现在 + 15 分钟」**。亮灭屏抖动（口袋里的接近感应、通知点亮的瞬间屏幕）如果每次都
     * 重排，15 分钟的倒计时就永远走不完 —— 心跳被饿死。30 秒足够滤掉这类抖动，而人手的一次
     * 亮屏→灭屏间隔通常远大于它。
     */
    private const val MIN_RESCHEDULE_INTERVAL_MS = 30_000L

    /** 当前已排的闹钟是按哪个屏幕状态排的；null = 本进程还没排过（或不知道）。 */
    @Volatile
    private var armedForScreenOn: Boolean? = null

    /** 上一次真正重排的时刻（elapsedRealtime）；0 = 本进程还没排过。 */
    @Volatile
    private var lastRescheduleAt = 0L

    /**
     * 屏幕是否亮着。
     *
     * 取不到 `PowerManager` 时按「亮着」返回：宁可保持原有的唤醒行为（保活能力优先），
     * 也不要因为一次取服务失败而把心跳降级成不唤醒。
     */
    fun isScreenOn(context: Context): Boolean = runCatching {
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isInteractive ?: true
    }.getOrDefault(true)

    /** 幂等注册；进程启动时调一次（`TemplateApp.onCreate`）。 */
    fun install(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }.onSuccess {
            registered = true
        }.onFailure {
            // 注册失败只意味着「锁屏期间仍在用唤醒闹钟」：省电效果打折，功能不受影响，
            // 所以只记一条 WARN，不抛、不改任何判定。
            LogStore.warn(TAG, "注册屏幕状态接收器失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 铁律与 KeepAliveReceiver 一致：接收器里绝不向外抛异常。
            runCatching {
                val app = context.applicationContext
                // 总开关关着就什么都不做，免得把闹钟又带回来。
                if (!ServicesGate.enabled(app)) return@runCatching
                // 用广播的 action 判定目标状态，而不是再读一次 PowerManager：
                // 广播是排队投递的，等我们处理时屏幕可能已经又变了一次，读到的会是那一次的值。
                val screenOn = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> true
                    Intent.ACTION_SCREEN_OFF -> false
                    else -> isScreenOn(app)
                }
                if (requestReschedule(app, screenOn)) {
                    LogStore.debug(TAG, "按「" + (if (screenOn) "亮屏" else "灭屏") + "」重排保活闹钟")
                }
            }.onFailure {
                runCatching {
                    LogStore.error(TAG, "处理屏幕状态变化失败：${it.message ?: it.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * 按新的屏幕状态决定要不要重排，返回是否真的排了。三条规则按顺序：
     *
     * 1. **闹钟不存在 ⇒ 一定排。** 这条是节流的安全阀：不管抖动多频繁，只要系统把闹钟清了
     *    （重启、强制停止、系统回收），下一次屏幕事件就会补上，绝不会「节流把心跳彻底节没」。
     * 2. **闹钟已经是按当前屏幕状态排的 ⇒ 不排。** 重复广播、状态没变都落在这里；
     *    重排只会把 15 分钟倒计时重置，对这个场景百害无一利。
     * 3. **状态确实变了、但距上次重排不到 [MIN_RESCHEDULE_INTERVAL_MS] ⇒ 不排**，且**不**更新
     *    [armedForScreenOn]：让「闹钟类型落后于屏幕状态」保持可见，下一次屏幕事件会补上。
     *    宁可短暂不匹配，也不要让倒计时被连续重置。
     */
    private fun requestReschedule(app: Context, screenOn: Boolean): Boolean {
        if (!KeepAliveScheduler.isScheduled(app)) {
            arm(app, screenOn)
            return true
        }
        if (armedForScreenOn == screenOn) return false
        val now = SystemClock.elapsedRealtime()
        if (lastRescheduleAt != 0L && now - lastRescheduleAt < MIN_RESCHEDULE_INTERVAL_MS) return false
        arm(app, screenOn)
        return true
    }

    private fun arm(app: Context, screenOn: Boolean) {
        KeepAliveScheduler.schedule(app)
        armedForScreenOn = screenOn
        lastRescheduleAt = SystemClock.elapsedRealtime()
    }
}
