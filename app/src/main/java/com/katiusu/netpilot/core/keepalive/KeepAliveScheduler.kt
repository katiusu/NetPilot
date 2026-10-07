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
 *
 * 1.5.4：**锁屏期间两类闹钟都换成不唤醒类型**（`ELAPSED_REALTIME`）——见 [schedule] 与
 * [scheduleRestartSoon] 里的注释。闹钟类型只能是「排的那一刻」决定的，所以屏幕亮/灭时由
 * [ScreenStateGate] 重排一次。
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

    /**
     * 重启退避的上限：与心跳周期取同一个值。
     *
     * 退避爬到 15 分钟就等于回到了心跳的兜底节奏（心跳每 15 分钟都会试着把服务拉起来），
     * 再往上加只会让真正需要拉回时等得更久，没有意义。
     */
    const val MAX_RESTART_DELAY_MS = HEARTBEAT_INTERVAL_MS

    /**
     * 服务「站稳」的时间窗口：活过它再出事，就当作全新一次，退避从 10 秒重新起步。
     *
     * 取 2 分钟是为了把「一启动就崩」和「OEM 省电策略刚起来就杀」与正常情况分开：
     * - 之前一直好好的（上次启动已超过 2 分钟）⇒ 用户从最近任务划掉、系统低内存清理
     *   都属于这类，10 秒拉回，行为与优化前完全一致；
     * - 2 分钟内又出事 ⇒ 判为连续失败，延迟翻倍。用户若在 2 分钟内连着划掉两次，
     *   第二次会等 20 秒而不是 10 秒 —— 这是唯一可见的差异，且远比崩溃风暴划算。
     */
    private const val RESTART_STABLE_WINDOW_MS = 2 * 60 * 1000L

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
        // 1.5.4：闹钟类型按屏幕状态选。
        //  - 亮屏：ELAPSED_REALTIME_WAKEUP —— 按「开机以来的时间」计时并在触发时唤醒 CPU，
        //    不受用户改系统时间影响（用 RTC 的话改时间会把闹钟弄乱）。
        //  - 锁屏：ELAPSED_REALTIME（**不唤醒**）。
        // 为什么非做不可：本应用会被用户（按应用内引导）加入「忽略电池优化」白名单，实测
        // `/data/system/deviceidle.xml` 里就有 com.katiusu.netpilot ⇒ Doze 对它的闹钟不做延迟，
        // 于是唤醒型心跳在息屏后照常把 CPU 从挂起里叫醒（每 15 分钟一次，≈96 次/天）。
        // 用户 2026-10 的要求是「锁屏时才停止定时唤醒」：亮屏时保持原样，锁屏后换成不唤醒类型
        // —— 设备因为别的原因醒着时它照常触发（服务被杀仍会被拉回），但绝不为了它唤醒 CPU。
        // 亮屏那一刻由 [ScreenStateGate] 重排回唤醒类型，保活节奏因此完全不变。
        val screenOn = ScreenStateGate.isScreenOn(app)
        runCatching {
            am.setInexactRepeating(
                if (screenOn) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                pending(app, REQUEST_HEARTBEAT, ACTION_HEARTBEAT),
            )
        }.onSuccess {
            LogStore.info(
                TAG,
                if (screenOn) {
                    "已注册保活心跳（每 ${HEARTBEAT_INTERVAL_MS / 60_000} 分钟，触发时唤醒 CPU）"
                } else {
                    "已注册保活心跳（每 ${HEARTBEAT_INTERVAL_MS / 60_000} 分钟；屏幕关闭，" +
                        "已改用不唤醒闹钟，亮屏后自动切回）"
                },
            )
        }.onFailure {
            LogStore.error(TAG, "注册保活心跳失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    /**
     * 排一个一次性闹钟，过 [delayMs] 后把自己拉回来。
     * 用于「用户从最近任务划掉」与「服务被销毁」这两条路径。
     *
     * [delayMs] 传负数（默认）表示「按指数退避自己算」：调用方是服务的 onDestroy /
     * onTaskRemoved，它并不知道这是第几次失败。需要固定延迟的老调用方传具体毫秒即可。
     */
    fun scheduleRestartSoon(context: Context, delayMs: Long = -1L) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        // 退避结果先算出来：无论下面排闹钟成功与否，失败历史都已经记下了。
        val delay = if (delayMs >= 0L) delayMs else nextBackoffDelay(app)
        val screenOn = ScreenStateGate.isScreenOn(app)
        runCatching {
            if (screenOn) {
                // setAndAllowWhileIdle：进 Doze 也会被放行（系统可能推迟到维护窗口），
                // 且不需要精确闹钟权限 —— setExactAndAllowWhileIdle 才是要权限的那个。
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delay,
                    pending(app, REQUEST_RESTART, ACTION_RESTART),
                )
            } else {
                // 1.5.4：锁屏期间的「拉回」同样不该把设备叫醒。改用不唤醒的一次性闹钟：
                // 语义（过 delay 后把服务拉回来）不变，只是设备恰好醒着时才执行。
                am.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + delay,
                    pending(app, REQUEST_RESTART, ACTION_RESTART),
                )
            }
        }.onSuccess {
            LogStore.debug(
                TAG,
                "已安排 ${delay / 1000} 秒后重启监控服务" + if (screenOn) "" else "（锁屏中，用不唤醒闹钟）",
            )
        }.onFailure {
            LogStore.error(TAG, "安排重启闹钟失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    /**
     * 指数退避：10 秒 → 20 秒 → 40 秒 → … 上限 [MAX_RESTART_DELAY_MS]。
     *
     * 为什么非做不可：服务如果「一启动就崩」，每次崩溃的 onDestroy 都会再排一个 10 秒闹钟，
     * 于是变成 10 秒一次的闹钟风暴 —— 它比这次优化里任何一个被砍掉的唤醒都贵得多。
     *
     * 判定依据是「服务上一次启动之后活了多久」（[KeepAliveState.lastServiceStartAt]）：
     *  - 活过 [RESTART_STABLE_WINDOW_MS] 再出事 ⇒ 全新一次，回到 10 秒
     *    （用户从最近任务划掉就属于这一类，所以「划掉后 10 秒拉回」的行为不变）；
     *  - 没活过 ⇒ 上一次的延迟翻倍，一路退到 15 分钟，把风暴压成一条慢心跳。
     */
    private fun nextBackoffDelay(app: Context): Long {
        val now = System.currentTimeMillis()
        val lastStart = KeepAliveState.lastServiceStartAt(app)
        val previous = KeepAliveState.restartDelayMs(app)
        val stable = lastStart > 0L && now - lastStart >= RESTART_STABLE_WINDOW_MS
        val next = if (stable || previous <= 0L) {
            DEFAULT_RESTART_DELAY_MS
        } else {
            (previous * 2).coerceAtMost(MAX_RESTART_DELAY_MS)
        }
        KeepAliveState.setRestartDelayMs(app, next)
        return next
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
