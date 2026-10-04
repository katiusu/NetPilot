package com.katiusu.netpilot.core.qs

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.mode.NetworkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 磁贴点击时循环切换的制式顺序：仅 5G → 5G/4G 自动 → 仅 4G → 4G/3G/2G 自动。
 *
 * 为什么不用 `NetworkMode.quickPresets`：那个常量的元素类型在特权层重构过程中
 * 出现过 `List<Int>` 与 `List<NetworkMode>` 两种形态，这里显式写死枚举常量
 * （四个取值都确认存在于 `core/mode/NetworkMode.kt`），本文件就不会被别人的
 * 重构带崩。顺序本身与契约给的常用制式集合一致。
 */
private val TileModeCycle: List<NetworkMode> = listOf(
    NetworkMode.NR_ONLY,
    NetworkMode.NR_LTE,
    NetworkMode.LTE_ONLY,
    NetworkMode.LTE_GSM_WCDMA,
)

/**
 * 「网络制式」快捷设置磁贴。
 *
 * 行为：
 * - `onStartListening()`：刷新磁贴外观（当前制式短名 + 可用性），此时系统才允许更新磁贴。
 * - `onClick()`：按 [TileModeCycle] 切到下一个制式并 Toast 结果；切换期间先把磁贴置为
 *   `STATE_UNAVAILABLE`，防止用户在等待时连点。
 * - 没有默认数据卡（subId < 0）→ `STATE_UNAVAILABLE` + 副标题提示。
 *
 * 线程模型：TileService 没有现成的生命周期协程作用域，`onClick()` 里既不能
 * `runBlocking`（会卡住 QS 面板的 binder 线程），也不该裸起线程。这里自建
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)`：`NetPilot` 的挂起函数
 * 内部自己会切到 IO，不需要我们占主线程；只有 Toast 需要 Looper，单独用
 * `withContext(Dispatchers.Main)` 兜一下。
 */
class NetworkModeTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 同一时刻只允许一个「渲染」或「切换」在跑，新任务进来就取消旧任务。 */
    private var pending: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        cycle()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 内部实现 ----------------

    private fun refresh() {
        pending?.cancel()
        pending = scope.launch { render() }
    }

    /** 把当前状态写进磁贴；`qsTile` 为空说明系统已不再持有磁贴，直接放弃。 */
    private suspend fun render() {
        // 读默认数据卡要跨 binder，放到协程里；失败按「没有数据卡」处理。
        val subId = runCatching { NetPilot.defaultDataSubId() }.getOrDefault(-1)
        val tile = qsTile ?: return

        tile.icon = Icon.createWithResource(this, R.drawable.ic_np_notification)
        tile.label = getString(R.string.qs_network_mode_label)

        if (subId < 0) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = getString(R.string.qs_network_mode_no_sim)
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.subtitle = runCatching { NetPilot.currentModeShort(subId) }
                .getOrDefault(getString(R.string.qs_network_mode_unknown))
        }
        tile.updateTile()
    }

    private fun cycle() {
        pending?.cancel()
        pending = scope.launch {
            val subId = runCatching { NetPilot.defaultDataSubId() }.getOrDefault(-1)
            if (subId < 0) {
                toast(getString(R.string.qs_network_mode_no_sim))
                render()
                return@launch
            }

            // currentModeValue 在通道不可用时返回 -1；此时 indexOfFirst 得到 -1，
            // 按「从循环第一项开始」处理，保证按一下一定有可预期的结果。
            val current = runCatching { NetPilot.currentModeValue(subId) }.getOrDefault(-1)
            val index = TileModeCycle.indexOfFirst { it.value == current }
            val next = if (index < 0) {
                TileModeCycle.first()
            } else {
                TileModeCycle[(index + 1) % TileModeCycle.size]
            }

            // 切换期间置为不可用 + 给出副标题，避免用户以为没反应而连点。
            qsTile?.apply {
                state = Tile.STATE_UNAVAILABLE
                subtitle = getString(R.string.qs_network_mode_busy)
                updateTile()
            }

            val ok = runCatching { NetPilot.setMode(subId, next) }.getOrDefault(false)
            if (ok) {
                toast(getString(R.string.qs_network_mode_switched, next.label))
            } else {
                // 失败时把当前特权通道一起告诉用户，便于判断是「没 Root」还是「运营商拒绝」。
                toast(getString(R.string.qs_network_mode_failed, NetPilot.channelLabel()))
            }
            render()
        }
    }

    private suspend fun toast(text: String) {
        withContext(Dispatchers.Main) {
            runCatching {
                Toast.makeText(this@NetworkModeTileService, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
