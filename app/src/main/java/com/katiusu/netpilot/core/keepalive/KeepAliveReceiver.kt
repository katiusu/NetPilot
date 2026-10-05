package com.katiusu.netpilot.core.keepalive

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.katiusu.netpilot.core.ServicesGate
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.monitor.MonitorService

/**
 * 保活闹钟的落点：心跳到点、或「刚被清理」的重启闹钟到点。
 *
 * 铁律：**任何异常都必须吞掉**。广播接收器里抛出异常会被系统记成应用崩溃，
 * 反复几次之后系统会直接禁用本应用的后台能力（自启、闹钟、广播全部失效）——
 * 那才是真正把保活做死。所以整个 onReceive 包在 runCatching 里。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            val app = context.applicationContext
            when (intent.action) {
                KeepAliveScheduler.ACTION_HEARTBEAT -> {
                    // 这里**刻意不再**重新注册心跳闹钟。
                    //
                    // 原实现每次心跳都调一次 KeepAliveScheduler.schedule()，理由是「闹钟可能被
                    // 系统清掉，重排一次只花一次 binder 调用」。但那条理由本身不成立：
                    //  - 心跳用的是 setInexactRepeating，系统会自己按周期续下去，不需要谁重排；
                    //  - 真被清掉时这个接收器根本不会被执行，「站在心跳里自我修复」永远修不到；
                    //  - 每次重排会把下一次触发时间推回「现在 + 15 分钟」，等于不断重置相位，
                    //    系统没法把这条周期闹钟稳定地并入 Doze 的批处理窗口。
                    // 心跳这一天 96 次里唯一还有意义的事只剩「确认服务活着，不在就拉起来」。
                    // 真正的补排路径另有两条，都不依赖心跳：MonitorService.onCreate()（服务每次
                    // 启动都会补一次，见那里的注释）与 BootReceiver（开机 / 应用更新时）。
                    ensureMonitorRunning(app, "保活心跳")
                }

                KeepAliveScheduler.ACTION_RESTART -> ensureMonitorRunning(app, "被清理后重启")

                else -> return
            }
        }.onFailure {
            // 连记日志都可能失败（LogStore 本身出问题），再兜一层，绝不向外抛。
            runCatching {
                LogStore.error(TAG, "保活广播处理失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /** 按「总开关 → 用户意图 → 当前是否已在跑」三步决定要不要拉起前台服务。 */
    private fun ensureMonitorRunning(app: Context, reason: String) {
        if (!ServicesGate.enabled(app)) {
            // 总开关关着就该什么都不做。不加这层判断，用户关掉总开关后 15 分钟内
            // 服务又会被心跳拉回来，开关就形同虚设。
            LogStore.debug(TAG, "$reason：后台服务总开关已关闭，不拉起监控服务")
            return
        }
        if (!KeepAliveState.monitorWanted(app)) {
            // 用户点过通知栏的「停止」（ACTION_STOP）时这里是 false：必须尊重，
            // 否则用户永远关不掉监控。
            LogStore.debug(TAG, "$reason：监控处于用户主动停止状态，不拉起")
            return
        }
        if (MonitorEngine.running.value) {
            LogStore.debug(TAG, "$reason：监控循环已在运行，无需处理")
            return
        }
        // Android 12+ 在后台启动前台服务受限，可能抛 ForegroundServiceStartNotAllowedException。
        //
        // 为什么必须单独 catch 这一种：外层 onReceive 的 runCatching 会把所有异常统一记成
        // 「保活广播处理失败：xxx」——用户只看到一句笼统的失败，既不知道是权限问题、也不知道
        // 自己能做什么。而这条异常有明确且用户可自解的成因（没关闭电池优化），所以它值得
        // 一条自己的、带下一步建议的 ERROR。重试没有意义（同样的后台状态会被同样拦下），
        // 能改善的只有「把原因说清楚」这一点。
        val started = runCatching {
            ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java))
        }.fold(
            onSuccess = { true },
            onFailure = { e ->
                if (e is ForegroundServiceStartNotAllowedException) {
                    LogStore.error(
                        TAG,
                        "$reason：系统拒绝了后台启动前台服务（未授予「忽略电池优化」）。" +
                            "重试同样会被拒绝，请到系统设置里为本应用关闭电池优化，等下一次心跳",
                    )
                } else {
                    LogStore.warn(
                        TAG,
                        "$reason：拉起前台服务失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }
                false
            },
        )
        // 只有真的起来了才记「已拉起」：原实现在调用之后无条件记成功，一旦被系统拦住，
        // 日志里会同时留下一条 ERROR 和一条「已拉起」，排查时自相矛盾。
        if (started) {
            LogStore.info(TAG, "$reason：已拉起监控前台服务")
        }
    }

    private companion object {
        const val TAG = "保活"
    }
}
