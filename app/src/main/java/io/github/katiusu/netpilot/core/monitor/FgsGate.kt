package io.github.katiusu.netpilot.core.monitor

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationCompat
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.core.priv.RootShell

/**
 * Android 17（targetSdk 36）起，specialUse 前台服务的门槛从「一条权限」变成了
 * 「权限 + app-op」两道门。
 *
 * a17 上 FOREGROUND_SERVICE_SPECIAL_USE 的权限位是 granted=true，但同名的 app-op
 * 默认是 ignore；AMS 校验 [android.app.Service.startForeground] 时看的是 app-op，
 * 于是抛出 SecurityException: Starting FGS with type specialUse ... requires permissions。
 * 服务在 onCreate 里就挂了，进程直接崩掉 —— 这就是 2.0.2 在 a17 上闪退的根因。
 *
 * 修复思路：能查就查、有 root 就补（appops set ... allow）、补不上就降级成
 * 普通通知并停掉服务，绝不把进程带走。app-op 的字符串必须是
 * android:foreground_service_special_use，短横线写法在 appops 命令里不认。
 */
object FgsGate {

    /** AppOpsManager 里的完整操作名；写成常量而不是引用 OPSTR_*，避免不同 ROM 的字段缺失。 */
    const val OP_SPECIAL_USE = "android:foreground_service_special_use"

    private const val TAG = "前台服务"

    const val ALERT_CHANNEL_ID = "netpilot_fgs_alert"
    const val ALERT_NOTIFICATION_ID = 1002
    const val ACTION_FIX = "io.github.katiusu.netpilot.action.FIX_FGS"

    /**
     * 当前进程的 specialUse app-op 是否放行。
     *
     * MODE_DEFAULT 视为「未知」而不是「拒绝」：有些 ROM 把默认值放在权限的
     * 默认 app-op 里（真机上仍然是放行），预判成拒绝会平白多跑一次 root 命令。
     * 查询本身失败时同样返回 true，让 startForeground 自己去试。
     */
    fun isSpecialUseAllowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = runCatching {
            ops.unsafeCheckOpNoThrow(OP_SPECIAL_USE, Process.myUid(), context.packageName)
        }.getOrNull() ?: return true
        return mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
    }

    /**
     * 用 root 把 specialUse 的 app-op 补成 allow。阻塞调用，必须放在 IO 线程。
     *
     * 只补自己这一条 op，不动别的权限；没有 root 时直接返回 false，由调用方降级。
     */
    fun heal(context: Context): Boolean {
        if (isSpecialUseAllowed(context)) return true
        if (!RootShell.hasRoot()) {
            LogStore.warn(TAG, "Android 17 拒绝了 specialUse 前台服务，且没有 root 可自动修复")
            return false
        }
        val pkg = context.packageName
        val result = runCatching {
            RootShell.exec(
                "appops set ${RootShell.quote(listOf(pkg))} FOREGROUND_SERVICE_SPECIAL_USE allow",
                8_000L,
            )
        }.getOrNull()
        if (result == null || result.code != 0 || result.timedOut) {
            LogStore.warn(
                TAG,
                "补授权失败：code=${result?.code ?: -1} ${result?.stderr?.trim().orEmpty()}",
            )
            return false
        }
        // 命令回 0 不代表生效：有的 ROM 的 appops 命令会静默忽略未知包名，必须回读。
        val ok = isSpecialUseAllowed(context)
        if (ok) {
            LogStore.info(TAG, "已用 root 补上 specialUse 前台服务授权（app-op allow）")
        } else {
            LogStore.warn(TAG, "appops set 返回成功但回读仍是拒绝")
        }
        return ok
    }

    /**
     * 前台服务被系统拒绝时给用户一个可点的通知：点通知正文或「立即修复」按钮
     * 都会发广播给 [FgsRepairReceiver] 去补授权，补好后自动把监控拉回来。
     */
    fun notifyBlocked(context: Context, detail: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.np_fgs_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.np_fgs_channel_desc)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)

            val fix = PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, FgsRepairReceiver::class.java).setAction(ACTION_FIX),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = context.getString(R.string.np_fgs_blocked_text)
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_np_notification)
                .setContentTitle(context.getString(R.string.np_fgs_blocked_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n$text"))
                .setAutoCancel(true)
                .setContentIntent(fix)
                .addAction(0, context.getString(R.string.np_fgs_action_fix), fix)
                .build()
            mgr.notify(ALERT_NOTIFICATION_ID, notification)
        }
    }

    /** 修复成功后把告警通知撤掉，别让「未启动」一直挂在通知栏。 */
    fun clearBlocked(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID)
        }
    }
}
