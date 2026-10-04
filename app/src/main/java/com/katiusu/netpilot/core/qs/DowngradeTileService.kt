package com.katiusu.netpilot.core.qs

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.monitor.MonitorPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「网络质量自动降级」快捷设置磁贴。
 *
 * 三态语义：
 * - 没有可用特权通道 → `STATE_UNAVAILABLE`：开了也写不进网络制式，显示成可用就是骗人。
 * - 通道可用但开关关闭 → `STATE_INACTIVE`。
 * - 通道可用且开关打开 → `STATE_ACTIVE`，副标题显示状态机当前阶段。
 *
 * 副标题单独走 `NetPilot.statusText()` 会太长（磁贴一行放不下），所以这里只放
 * 状态机阶段；完整状态（RSRP/Ping）留给监控页和通知。
 */
class DowngradeTileService : TileService() {

    /** 见 [NetworkModeTileService] 的线程模型说明。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            // toggleAutoDowngrade 返回切换后的新状态；抛异常时退化成读当前值，避免磁贴停在旧状态。
            val enabled = runCatching { NetPilot.toggleAutoDowngrade(this@DowngradeTileService) }
                .getOrDefault(NetPilot.autoDowngradeEnabled())
            toast(
                getString(
                    if (enabled) R.string.qs_downgrade_toast_on else R.string.qs_downgrade_toast_off
                )
            )
            render()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 内部实现 ----------------

    /**
     * 有没有可用的特权通道。
     *
     * 刻意不去比对「无通道」那句话：`ControlManager.cachedLabel()` 的磁盘实现把
     * 「没有通道」写成了 `"无特权通道"`，而契约描述里写的是 `"无"`，按字面匹配迟早会错。
     * 通道短名只有 `Root` / `Shizuku` 两种正值，改成正向判断；`MonitorEngine.channelLabel`
     * 的初值「未探测」自然也会落到「不可用」这一侧。
     */
    private fun hasPrivilegedChannel(): Boolean =
        NetPilot.channelLabel() in KNOWN_CHANNEL_LABELS

    /** 磁贴状态与副标题都在这里决定，`onStartListening` / 点击后都走同一条路径。 */
    private fun render() {
        val tile = qsTile ?: return
        val enabled = NetPilot.autoDowngradeEnabled()
        val usableChannel = hasPrivilegedChannel()

        tile.icon = Icon.createWithResource(this, R.drawable.ic_np_notification)
        tile.label = getString(R.string.qs_downgrade_label)
        tile.state = when {
            !usableChannel -> Tile.STATE_UNAVAILABLE
            enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !usableChannel -> getString(R.string.qs_downgrade_no_channel)
            !enabled -> getString(R.string.qs_downgrade_off)
            else -> getString(
                phaseRes(
                    MonitorEngine.downgrade.value.phase(
                        System.currentTimeMillis(),
                        NetPilot.thresholds(),
                    )
                )
            )
        }
        tile.updateTile()
    }

    private fun phaseRes(phase: MonitorPhase): Int = when (phase) {
        MonitorPhase.OFF -> R.string.qs_downgrade_off
        MonitorPhase.WATCHING -> R.string.qs_downgrade_watching
        MonitorPhase.DOWNGRADED_COOLDOWN -> R.string.qs_downgrade_cooldown
        MonitorPhase.RECOVERING -> R.string.qs_downgrade_recovering
        MonitorPhase.ROLLBACK -> R.string.qs_downgrade_rollback
    }

    private suspend fun toast(text: String) {
        withContext(Dispatchers.Main) {
            runCatching {
                Toast.makeText(this@DowngradeTileService, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        /** 特权通道的已知短名（`ControlManager.ControlMethod.ROOT` / `SHIZUKU` 的展示名）。 */
        val KNOWN_CHANNEL_LABELS = setOf("Root", "Shizuku")
    }
}
