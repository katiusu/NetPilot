package io.github.katiusu.netpilot.core.priv

import io.github.katiusu.netpilot.prefs.ConfigState

/**
 * 联发科 AT 直写通道的开关与标记。
 *
 * 为什么单独一个对象而不是塞进 MonitorSettings：这条通道属于特权层，
 * 由 RootController 在执行写入时读它，让 priv 反过来依赖 monitor 会形成包循环。
 *
 * 开关默认 false。实验通道必须显式打开 —— 它绕过 framework 直接改 modem，
 * 写错就是没网，不能靠「用户装了联发科手机」自动开启。
 */
object AtWriteSettings {

    /** 用户可见开关。默认关。 */
    const val KEY_ENABLED = "np_at_write_enabled"

    private const val KEY_LAST_MODE = "np_at_last_mode"
    private const val KEY_LAST_AT_MS = "np_at_last_at_ms"

    /** 多久之内算「刚刚由 AT 写过」。 */
    const val RECENT_WINDOW_MS = 10_000L

    fun enabled(): Boolean = ConfigState.bool(KEY_ENABLED, false)

    fun setEnabled(on: Boolean) {
        ConfigState.set(KEY_ENABLED, on)
    }

    /** 记下「刚刚用 AT 写成功了一次」，上层据此不再报「写入未被系统接受」。 */
    fun markApplied(modeValue: Int, atMs: Long = System.currentTimeMillis()) {
        ConfigState.set(KEY_LAST_MODE, modeValue)
        ConfigState.set(KEY_LAST_AT_MS, atMs.toString())
    }

    /** 最近一次 AT 写入的制式值；没有写过返回 -1。 */
    fun lastAppliedMode(): Int = ConfigState.int(KEY_LAST_MODE, -1)

    /** windowMs 内是否刚由 AT 写成功。 */
    fun recentlyApplied(now: Long = System.currentTimeMillis(), windowMs: Long = RECENT_WINDOW_MS): Boolean {
        val at = ConfigState.string(KEY_LAST_AT_MS, "0").toLongOrNull() ?: return false
        if (at <= 0L) return false
        return now - at in 0..windowMs
    }
}
