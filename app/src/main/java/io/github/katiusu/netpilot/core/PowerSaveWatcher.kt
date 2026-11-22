package io.github.katiusu.netpilot.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import io.github.katiusu.netpilot.core.monitor.LogStore

/**
 * 系统省电模式感知（2.0.2）。
 *
 * 为什么单独抽一个观察者：省电模式影响三个互不相干的层——监控循环的采样间隔
 * （MonitorEngine）、动态背景的动画帧率（BgEffectModifier）和毛玻璃
 * （rememberBlurBackdrop）。三处各自去 getSystemService(POWER_SERVICE) 会重复查、
 * 还可能在不同时刻读到不同值；统一在这里落一份进程内状态，注册一次广播，变化时写日志。
 *
 * 用 @Volatile 而不是 StateFlow 是刻意的：读它的都是**每帧**路径（动画循环、绘制），
 * 拿一个 volatile 字段不引入协程调度与订阅开销。
 */
object PowerSaveWatcher {

    private const val TAG = "省电模式"

    @Volatile
    private var registered = false

    @Volatile
    private var powerSave = false

    /**
     * 当前是否处于系统省电模式。
     *
     * 取不到 PowerManager（或被系统拒绝查询）时按 false 返回：宁可多一点功耗，
     * 也不要因为一次读服务失败就把界面降成省电画质。
     */
    val isPowerSave: Boolean
        get() = powerSave

    /** 幂等注册；进程启动时调一次（见 TemplateApp.onCreate）。 */
    fun install(context: Context) {
        val app = context.applicationContext
        powerSave = readState(app)
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
                update(readState(ctx ?: app))
            }
        }
        runCatching {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure {
            // 注册失败不影响功能：isPowerSave 会一直停在启动时读到的值（通常是 false）。
            LogStore.warn(TAG, "注册省电模式监听失败：" + (it.message ?: it.javaClass.simpleName))
        }
    }

    private fun readState(context: Context): Boolean = runCatching {
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode ?: false
    }.getOrDefault(false)

    private fun update(enabled: Boolean) {
        if (powerSave == enabled) return
        powerSave = enabled
        LogStore.info(
            TAG,
            if (enabled) {
                "进入系统省电模式：动态背景锁 60 帧、关闭毛玻璃、采样间隔翻倍"
            } else {
                "退出系统省电模式：动画跟随刷新率、恢复毛玻璃与原采样间隔"
            },
        )
    }
}
