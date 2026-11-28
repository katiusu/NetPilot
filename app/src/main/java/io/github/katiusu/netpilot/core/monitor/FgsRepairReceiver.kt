package io.github.katiusu.netpilot.core.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.ServicesGate
import io.github.katiusu.netpilot.core.keepalive.KeepAliveState

/**
 * 「监控未启动」通知上的修复入口。
 *
 * 补授权要跑 root 命令，必须离开主线程；onReceive 里用 goAsync 把结果交回系统，
 * 否则接收器一返回，进程随时可能被回收，命令白跑。
 */
class FgsRepairReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FgsGate.ACTION_FIX) return
        val appContext = context.applicationContext
        if (FgsGate.isSpecialUseAllowed(appContext)) {
            LogStore.info("前台服务", "收到修复请求：授权已正常，无需处理")
            FgsGate.clearBlocked(appContext)
            resumeMonitor(appContext)
            return
        }
        val pending = goAsync()
        Thread({
            try {
                if (FgsGate.heal(appContext)) {
                    FgsGate.clearBlocked(appContext)
                    resumeMonitor(appContext)
                } else {
                    FgsGate.notifyBlocked(appContext, appContext.getString(R.string.np_fgs_fix_failed))
                }
            } catch (t: Throwable) {
                LogStore.error("前台服务", "修复前台服务授权失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }, "np-fgs-repair").start()
    }

    /** 用户确实要监控（开关开着、没点过停止）时把服务重新拉起来。 */
    private fun resumeMonitor(context: Context) {
        if (ServicesGate.enabled(context) && KeepAliveState.monitorWanted(context)) {
            MonitorEngine.setEnabled(context, true)
        }
    }
}
