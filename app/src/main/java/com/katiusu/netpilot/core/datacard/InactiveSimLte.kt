package com.katiusu.netpilot.core.datacard

import android.content.Context
import android.os.SystemClock
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.prefs.ConfigState
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.core.monitor.MonitorSettings
import com.katiusu.netpilot.core.priv.ControlManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 「非活跃数据卡自动设为仅 4G」守护（1.6.0 新增）。
 *
 * 用户要的语义（已确认）：**常驻守护、每轮纠正** —— 不是「打开开关那一刻写一次」。
 * 因为系统会在用户手动改、重启、换卡、运营商配置刷新时把制式改回去，
 * 一次性的写法等于什么都没做。所以这里挂在 [com.katiusu.netpilot.core.NetPilotEvents.onSample]
 * 上，每轮采样顺带核对一次。
 *
 * 几个刻意的边界：
 * - **只写非上网卡**：上网卡（默认数据卡）的制式由网络质量降级那套逻辑负责，这里碰它会互相打架。
 * - **每张卡 5 分钟最多尝试一次**：采样最短 30 秒一轮，不节流的话在某台「写完立刻被改回」的机型上
 *   会变成每轮一次特权调用 + 一次写日志，耗电与刷屏都不可接受；5 分钟已经能保证「每轮纠正」的观感。
 * - **读不到当前制式就不写**：`getMode` 返回 -1 说明特权通道此刻不可用，盲写只会写失败并刷一条 WARN。
 */
object InactiveSimLte {

    private const val TAG = "次卡 4G"

    /** 目标制式：仅 4G（LTE）。与设置页「锁定 4G」用的是同一个模式值。 */
    private val TARGET_MODE = NetworkMode.LTE_ONLY

    /** 同一张卡的两次写入尝试之间的最小间隔。 */
    private const val WRITE_INTERVAL_MS = 5 * 60 * 1000L

    /** subId → 上次尝试写入的时刻（elapsedRealtime，不受用户改系统时间影响）。 */
    private val lastTryAt = ConcurrentHashMap<Int, Long>()

    /**
     * 每轮采样后调用一次。任何异常都由调用方兜住（见 NetPilot.install 的 onSample 接线），
     * 这里只负责「按条件写一次」。
     */
    suspend fun guard(context: Context) {
        if (!ConfigState.bool(
                MonitorSettings.KEY_SUBSIM_AUTO_LTE,
                MonitorSettings.DEFAULT_SUBSIM_AUTO_LTE,
            )
        ) {
            return
        }

        val sims = SimReader.sims(context)
        // 单卡设备没有「次卡」，直接返回：这是最常见的情况，不值得每次打日志。
        if (sims.size < 2) return

        val autoDetect = ConfigState.bool(
            MonitorSettings.KEY_SUBSIM_AUTO_DETECT,
            MonitorSettings.DEFAULT_SUBSIM_AUTO_DETECT,
        )
        val manualSubId = ConfigState.int(
            MonitorSettings.KEY_SUBSIM_MANUAL_SUB_ID,
            MonitorSettings.DEFAULT_SUBSIM_MANUAL_SUB_ID,
        )
        // 上网卡编号：自动检测时问系统，否则用用户指定的卡号。
        val primarySubId = if (autoDetect) SimReader.defaultDataSubId() else manualSubId
        if (primarySubId <= 0) {
            // 不知道哪张是上网卡 ⇒ 宁可不写。猜错方向会把上网卡锁到 4G，代价远大于不生效。
            LogStore.debug(TAG, "拿不到上网卡编号（自动检测=$autoDetect，指定=$manualSubId），本轮跳过")
            return
        }

        val now = SystemClock.elapsedRealtime()
        for (sim in sims) {
            if (sim.subId == primarySubId) continue

            val last = lastTryAt[sim.subId] ?: 0L
            if (now - last < WRITE_INTERVAL_MS) continue

            val current = ControlManager.getMode(sim.subId)
            if (current < 0) {
                LogStore.debug(TAG, "次卡 ${sim.title} 当前制式读不到（特权通道不可用），本轮跳过")
                continue
            }
            if (current == TARGET_MODE.value) continue

            lastTryAt[sim.subId] = now
            val ok = ControlManager.setMode(sim.subId, TARGET_MODE)
            if (ok) {
                LogStore.info(
                    TAG,
                    "次卡 ${sim.title} 已设为 ${TARGET_MODE.label}" +
                        "（原 ${NetworkMode.labelOf(current)}）",
                )
            } else {
                LogStore.warn(
                    TAG,
                    "次卡 ${sim.title} 设为 ${TARGET_MODE.label} 失败" +
                        "（当前 ${NetworkMode.labelOf(current)}），${WRITE_INTERVAL_MS / 60_000} 分钟后重试",
                )
            }
        }
    }
}
