package com.katiusu.netpilot.core.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.katiusu.netpilot.MainActivity
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.ServicesGate
import com.katiusu.netpilot.core.keepalive.KeepAliveScheduler
import com.katiusu.netpilot.core.keepalive.KeepAliveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 常驻监控的前台服务。
 *
 * 为什么必须是前台服务：网络质量检测要 2 分钟一次持续采样（还要跑 TCP 探测），
 * 普通后台任务在息屏后会被 Doze 冻结，采样间隔变成不可预测，降级时机就废了。
 * 前台服务 + 常驻通知是唯一能保证「息屏后仍在按点采样」的合规做法。
 */
class MonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "netpilot_monitor"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.katiusu.netpilot.action.STOP_MONITOR"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须在 onCreate 里就 startForeground：Android 12+ 要求前台服务启动后
        // 5 秒内挂出通知，否则直接 ANR/崩。
        startForeground(NOTIFICATION_ID, buildNotification("正在监控网络质量…"))
        // 服务被启动 = 有人要它跑（用户开关 / 磁贴 / Tasker / 开机自启 / 保活）。
        // 这条运行期意图是保活的判据：只有用户从通知栏点「停止」才会被置回 false，
        // 否则心跳会在 15 分钟后把用户刚停掉的服务拉回来。
        KeepAliveState.setMonitorWanted(this, true)
        // 给重启退避留一个时间戳：下一次 onDestroy 排「尽快拉回」时，要靠它判断
        // 这一次启动到底站没站稳（活过窗口就从 10 秒重来，没活过就翻倍退避）。
        KeepAliveState.noteServiceStarted(this)
        // 心跳可能在「总开关关闭期间」被系统清掉（重启/省电），服务活着时补一次最省事。
        // 这也是心跳闹钟**唯一**的补排路径（心跳接收器里已经不再重排，见 KeepAliveReceiver）。
        if (ServicesGate.enabled(this)) {
            KeepAliveScheduler.schedule(this)
        }
        scope.launch {
            MonitorEngine.snapshot.collectLatest { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LogStore.info("监控服务", "用户从通知栏停止监控")
            // 用户主动停：撤掉运行期意图。不置 false 的话，下一次心跳（≤15 分钟）
            // 就会把服务拉回来，用户会发现「怎么关都关不掉」——
            // 保活要防的是系统清理，不是用户的关闭动作。
            KeepAliveState.setMonitorWanted(this, false)
            stopSelf()
            // START_NOT_STICKY：用户停掉的服务不该被系统按 sticky 规则重启。
            return START_NOT_STICKY
        }
        MonitorEngine.startLoop(this, "前台服务")
        return START_STICKY
    }

    /**
     * 用户从最近任务列表划掉应用/服务。
     *
     * 这里**不**调 stopSelf：前台服务不一定会因划掉而被杀（有时只是 Activity 被清），
     * 主动停掉反而会中断正在进行的监控。只排一个「尽快拉回」的闹钟当兜底：
     * 真被杀了就 10 秒后由 [com.katiusu.netpilot.core.keepalive.KeepAliveReceiver] 拉回，
     * 没被杀的话闹钟触发时监控循环已在跑，接收器会直接跳过。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (ServicesGate.enabled(this) && KeepAliveState.monitorWanted(this)) {
            KeepAliveScheduler.scheduleRestartSoon(this)
            LogStore.info("监控服务", "应用被划掉，已安排自愈重启")
        }
    }

    override fun onDestroy() {
        MonitorEngine.stopLoop("前台服务结束")
        scope.cancel()
        // 服务被销毁有两种可能：系统省电/内存清理，或用户主动停（那时 monitorWanted 已是
        // false）。用 wanted 区分，才能既做到「被杀能拉回来」又做到「用户能真正关掉」。
        // 总开关的判断不能省：关总开关时是先落配置再 stopService，onDestroy 在这里
        // 读到的已经是「关闭」，于是不会再排一个没人需要的重启闹钟。
        if (ServicesGate.enabled(this) && KeepAliveState.monitorWanted(this)) {
            KeepAliveScheduler.scheduleRestartSoon(this)
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.np_monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.np_monitor_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_np_notification)
            .setContentTitle(getString(R.string.np_monitor_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.np_action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 上一次真正推到通知栏的文本；null 表示还没推过。 */
    @Volatile
    private var lastNotificationText: String? = null

    private fun updateNotification(snap: SignalSnapshot?) {
        val text = if (snap == null) {
            "等待首次采样…"
        } else {
            buildString {
                append(snap.networkType)
                append(" · ")
                append(snap.rsrp?.let { "RSRP $it dBm" } ?: "RSRP 未知")
                append(" · ")
                append(
                    snap.pingMs?.let { "$it ms" }
                        // 跳过探测的轮次说明原因：锁屏上看到「无响应」会以为断网了。
                        ?: if (snap.probeSkipped) {
                            getString(R.string.monitor_value_ping_skipped)
                        } else {
                            "无响应"
                        },
                )
            }
        }
        // 文本没变就直接返回，不再碰 NotificationManager。
        //
        // 为什么需要这层判断：MonitorEngine.publish() 每轮都写 _lastTickAt，StateFlow 因此
        // 每轮都发射，上面那个 collectLatest 于是每轮（默认 60 秒一轮；监控页开着 5 秒快采时
        // 更密）都会调到这里。而通知文本只由「网络类型 / RSRP / ping」三项决定，静止场景下
        // 绝大多数轮次三者一字不变，却仍然每次都走一次 NotificationManager binder 调用外加
        // 重新构建一个 Notification 对象。文本没变时跳过，用户看到的通知内容完全一样。
        if (text == lastNotificationText) return
        lastNotificationText = text
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        runCatching { mgr.notify(NOTIFICATION_ID, buildNotification(text)) }
    }
}
