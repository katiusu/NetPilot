package com.katiusu.netpilot.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.katiusu.netpilot.core.keepalive.KeepAliveScheduler
import com.katiusu.netpilot.core.keepalive.KeepAliveState
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.monitor.MonitorSettings
import com.katiusu.netpilot.prefs.ConfigState

/**
 * 开机自启 / 应用更新后自愈。
 *
 * 只做三件事：把内核装起来、按设置决定要不要重新拉起监控前台服务、补注册保活心跳。
 * 真正的「上次退出时停在降级态」这类自愈由 [MonitorEngine.startLoop] 里的
 * selfHeal 负责，这里不重复实现，避免两处状态修复逻辑互相打架。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> Unit
            else -> return
        }

        val app = context.applicationContext
        runCatching {
            NetPilot.install(app)

            // 总开关关着：不只是跳过启动，还要把闹钟撤干净 ——
            // 否则「关机前关掉总开关、开机后闹钟还在」会让开关名存实亡。
            if (!ServicesGate.enabled(app)) {
                KeepAliveScheduler.cancel(app)
                LogStore.info(TAG, "后台服务总开关已关闭，跳过自启并撤销保活闹钟")
                return
            }

            // 开机/应用更新都会清空 AlarmManager 里的闹钟，必须在这里补注册，
            // 不然保活链恰好断在「重启」这个最常见的场景上。
            KeepAliveScheduler.schedule(app)

            if (!ConfigState.bool(MonitorSettings.KEY_AUTO_START, true)) {
                // 「开机自启」关掉时必须连运行期意图一起撤掉：只要 monitorWanted 还是 true，
                // 15 分钟后的心跳就会把服务拉回来，这个开关就形同虚设。
                KeepAliveState.setMonitorWanted(app, false)
                LogStore.info(TAG, "开机自启已关闭，跳过监控启动")
                return
            }
            LogStore.info(TAG, "开机自启：恢复网络监控")
            // 先记意图再起服务：服务启动过程中若被系统打断，意图也已经落盘。
            KeepAliveState.setMonitorWanted(app, true)
            MonitorEngine.setEnabled(app, true)
        }.onFailure {
            LogStore.error(TAG, "开机自启失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "开机自启"
    }
}
