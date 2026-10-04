package com.katiusu.netpilot.core.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.katiusu.netpilot.core.monitor.LogStore

/**
 * 保活闹钟：只用系统 `AlarmManager`，不引 WorkManager（本工程不加依赖）。
 *
 * 为什么坚决不用 `setExact*`：精确闹钟要 `SCHEDULE_EXACT_ALARM` 权限，
 * 本工程没声明、也不该为一个「防止被杀后台」的心跳去申请这种高危权限
 * （Android 14 起它默认就是拒绝的，用户还得去系统设置里手动放行）。
 * 心跳迟到几分钟对保活没有任何实质影响，用不精确闹钟换来的是「装上就能用」。
 *
 * 本类只负责「排/撤闹钟」，不判断总开关、不启动服务 —— 那些在
 * [KeepAliveReceiver] 与 [com.katiusu.netpilot.core.NetPilot.setServicesEnabled] 里，
 * 这样它保持成一个可以独立测试的纯调度器。
 */
object KeepAliveScheduler {

    /**
     * 心跳动作。**必须**与 `AndroidManifest.xml` 里 `.core.keepalive.KeepAliveReceiver`
     * 的 intent-filter 完全一致，否则闹钟会照常触发但没人接收（静默失效）。
     */
    const val ACTION_HEARTBEAT = "com.katiusu.netpilot.action.KEEPALIVE_HEARTBEAT"

    /** 「刚被清理，尽快拉回」动作，同样必须与 Manifest 一致。 */
    const val ACTION_RESTART = "com.katiusu.netpilot.action.KEEPALIVE_RESTART"

    /** 心跳周期 15 分钟：够密（被杀后最多 15 分钟被纠回来），又不会明显耗电。 */
    const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * 「尽快拉回」的默认延迟。
     *
     * 取 10 秒而不是 1 秒：给系统一点时间把进程收干净，也避免「服务一启动就崩」
     * 时形成 1 秒一次的重启风暴（每次崩溃都会排下一个闹钟）。
     */
    const val DEFAULT_RESTART_DELAY_MS = 10 * 1000L

    /** 两个 request code 必须不同，否则两种闹钟会互相覆盖（PendingIntent 按 requestCode + Intent 去重）。 */
    private const val REQUEST_HEARTBEAT = 0x4B41 // "KA"
    private const val REQUEST_RESTART = 0x4B42 // "KB"

    private const val TAG = "保活闹钟"

    /** 注册周期性心跳。重复调用会重置计时（所以调用方应先问 [isScheduled]）。 */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        if (am == null) {
            LogStore.warn(TAG, "取不到 AlarmManager，保活心跳未注册")
            return
        }
        runCatching {
            am.setInexactRepeating(
                // ELAPSED_REALTIME_WAKEUP：按「开机以来的时间」计时并在触发时唤醒 CPU，
                // 不受用户改系统时间影响（用 RTC 的话改时间会把闹钟弄乱）。
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                pending(app, REQUEST_HEARTBEAT, ACTION_HEARTBEAT),
            )
        }.onSuccess {
            LogStore.info(TAG, "已注册保活心跳（每 ${HEARTBEAT_INTERVAL_MS / 60_000} 分钟）")
        }.onFailure {
            LogStore.error(TAG, "注册保活心跳失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    /**
     * 排一个一次性闹钟，过 [delayMs] 后把自己拉回来。
     * 用于「用户从最近任务划掉」与「服务被销毁」这两条路径。
     */
    fun scheduleRestartSoon(context: Context, delayMs: Long = DEFAULT_RESTART_DELAY_MS) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            // setAndAllowWhileIdle：进 Doze 也会被放行（系统可能推迟到维护窗口），
            // 且不需要精确闹钟权限 —— setExactAndAllowWhileIdle 才是要权限的那个。
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pending(app, REQUEST_RESTART, ACTION_RESTART),
            )
        }.onSuccess {
            LogStore.debug(TAG, "已安排 ${delayMs / 1000} 秒后重启监控服务")
        }.onFailure {
            LogStore.error(TAG, "安排重启闹钟失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    /** 撤掉全部待发闹钟（心跳 + 重启）。 */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        if (am == null) {
            LogStore.warn(TAG, "取不到 AlarmManager，无法撤销保活闹钟")
            return
        }
        listOf(
            REQUEST_HEARTBEAT to ACTION_HEARTBEAT,
            REQUEST_RESTART to ACTION_RESTART,
        ).forEach { (requestCode, action) ->
            // 用 FLAG_NO_CREATE 取回已有的 PendingIntent：没有待发闹钟时不要凭空创建一个，
            // 否则「取消」反而会注册一个新的 PendingIntent 记录。
            val pi = existing(app, requestCode, action) ?: return@forEach
            runCatching { am.cancel(pi) }
            runCatching { pi.cancel() }
        }
        LogStore.info(TAG, "已撤销全部保活闹钟")
    }

    /** 心跳闹钟是否已注册。设置页的状态行直接显示它。 */
    fun isScheduled(context: Context): Boolean =
        existing(context.applicationContext, REQUEST_HEARTBEAT, ACTION_HEARTBEAT) != null

    // ------------------------------------------------------------------ 内部

    /**
     * 显式指定接收器组件（而不是只带 action 的隐式广播）：
     * 隐式广播一旦 action 字符串与 Manifest 写岔了就会静默失效，排障成本极高。
     */
    private fun alarmIntent(context: Context, action: String): Intent =
        Intent(context, KeepAliveReceiver::class.java).setAction(action)

    private fun pending(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            alarmIntent(context, action),
            // FLAG_IMMUTABLE 是 Android 12+ 的硬要求（可变 PendingIntent 会被直接拒绝）；
            // 查询侧也用同样的 flag，否则匹配不上。
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun existing(context: Context, requestCode: Int, action: String): PendingIntent? =
        runCatching {
            PendingIntent.getBroadcast(
                context,
                requestCode,
                alarmIntent(context, action),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
}
