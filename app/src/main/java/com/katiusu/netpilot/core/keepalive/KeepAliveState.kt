package com.katiusu.netpilot.core.keepalive

import android.content.Context
import com.katiusu.netpilot.prefs.PrefsStore

/**
 * 「监控服务此刻本应在跑」这条**运行期意图**的持久化记录（不是用户可见配置）。
 *
 * 为什么必须有它：保活要成立，既要「被杀了能拉回来」，也要「用户能真正关掉」。
 * 用户点通知栏的「停止」（[com.katiusu.netpilot.core.monitor.MonitorService.ACTION_STOP]）
 * 之后，如果 15 分钟一次的心跳闹钟照旧把服务拉起来，用户就永远关不掉监控 ——
 * 所以必须有一处能够区分「被系统杀了」和「用户主动停」。规则：
 *
 *  - 服务被**显式启动**（用户点开关 / 磁贴 / Tasker / 开机自启 / 总开关重新打开）时置 `true`；
 *  - 只有 ACTION_STOP（用户主动停）时才置 `false`。
 *
 * 心跳与重启闹钟在拉起服务之前都先问这个值。
 *
 * 键名用 `runtime_` 前缀是有意的：[com.katiusu.netpilot.prefs.ConfigBackup] 会跳过该前缀，
 * 它是「这台设备此刻的状态」，不该被导出到另一台设备上（否则一导入就自启监控）。
 * 走 [PrefsStore] 而不是 `ConfigState`：它不需要驱动 Compose 重组。
 */
object KeepAliveState {

    private const val KEY_MONITOR_WANTED = "runtime_monitor_wanted"

    /** 默认 false：全新安装、「从没让监控跑过」时，心跳不该把服务拉起来。 */
    fun monitorWanted(context: Context): Boolean {
        ensureLoaded(context)
        return runCatching { PrefsStore.getBoolean(KEY_MONITOR_WANTED, false) }.getOrDefault(false)
    }

    fun setMonitorWanted(context: Context, wanted: Boolean) {
        ensureLoaded(context)
        runCatching { PrefsStore.put(KEY_MONITOR_WANTED, wanted) }
    }

    // ------------------------------------------------------------------ 重启退避状态
    //
    // 这两个键同样是 `runtime_` 前缀：它们是「这台设备此刻的重启历史」，不该被导出到别的设备。

    /** 上一次「尽快拉回」用的延迟（毫秒）。0 表示还没有退避记录。 */
    private const val KEY_RESTART_DELAY_MS = "runtime_restart_delay_ms"

    /** 监控服务最后一次启动的墙钟时刻。退避只看「上一次启动之后活了多久」。 */
    private const val KEY_LAST_START_AT = "runtime_last_start_at"

    /**
     * 服务每次 `onCreate` 都记一笔。
     *
     * 为什么需要这个时间戳：判断服务是「被系统清理（说明之前一直好好的）」还是
     * 「一启动就没（崩溃循环）」—— 前者要立刻拉回，后者必须退避。
     */
    fun noteServiceStarted(context: Context) {
        ensureLoaded(context)
        runCatching { PrefsStore.put(KEY_LAST_START_AT, System.currentTimeMillis()) }
    }

    /** 服务最后一次启动的墙钟时刻，没记录过返回 0。 */
    fun lastServiceStartAt(context: Context): Long {
        ensureLoaded(context)
        return runCatching { PrefsStore.getLong(KEY_LAST_START_AT, 0L) }.getOrDefault(0L)
    }

    /** 上一次用的重启延迟，没退避过返回 0。 */
    fun restartDelayMs(context: Context): Long {
        ensureLoaded(context)
        return runCatching { PrefsStore.getLong(KEY_RESTART_DELAY_MS, 0L) }.getOrDefault(0L)
    }

    fun setRestartDelayMs(context: Context, delayMs: Long) {
        ensureLoaded(context)
        runCatching { PrefsStore.put(KEY_RESTART_DELAY_MS, delayMs) }
    }

    private fun ensureLoaded(context: Context) {
        runCatching { PrefsStore.init(context.applicationContext) }
    }
}
