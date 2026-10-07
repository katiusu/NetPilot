package com.katiusu.netpilot.core.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.katiusu.netpilot.core.ServicesGate
import com.katiusu.netpilot.core.monitor.LogStore

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
                if (ServicesGate.enabled(app)) KeepAliveScheduler.schedule(app)
            }.onFailure {
                runCatching {
                    LogStore.error(TAG, "处理屏幕状态变化失败：${it.message ?: it.javaClass.simpleName}")
                }
            }
        }
    }
}
