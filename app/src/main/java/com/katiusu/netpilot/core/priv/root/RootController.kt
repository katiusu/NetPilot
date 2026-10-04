package com.katiusu.netpilot.core.priv.root

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.priv.ChannelStatus
import com.katiusu.netpilot.core.priv.ControlMethod
import com.katiusu.netpilot.core.priv.NetworkControlChannel
import com.katiusu.netpilot.core.priv.RootShell
import com.katiusu.netpilot.core.priv.SubscriptionSwitcher
import com.katiusu.netpilot.core.priv.WriteVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root 通道，两条下发路径：
 *
 * 1. **首选**：`app_process` 执行 [com.katiusu.netpilot.core.priv.PrivilegedCli]，
 *    结果从 stdout 的单行协议里解析。
 *    ```
 *    CLASSPATH='<packageCodePath>' app_process /system/bin \
 *        com.katiusu.netpilot.core.priv.PrivilegedCli getmode 1
 *    ```
 * 2. **兜底**：`settings put/get global preferred_network_mode[1]`。
 *
 * 为什么要兜底：`CLASSPATH=... app_process` 这条路径的可用性依赖 ROM，本机无法离线验证；
 * 而 `settings put global preferred_network_mode` 是 Network_Enhance（MIT）在 HyperOS 上
 * 验证过仍会被电话进程的 ContentObserver 捕获并重新下发制式的写法。
 * [probe] 时若 app_process 不可用但 settings 可读写，本通道依旧标记为可用。
 */
class RootController(private val context: Context) : NetworkControlChannel {

    override val label: String = "Root"

    override val method: ControlMethod = ControlMethod.ROOT

    /** app_process 是否可用；探测判定为不可用时，所有操作直接走 settings 兜底。 */
    @Volatile
    private var cliUsable = true

    override fun isConnected(): Boolean = RootShell.hasRoot()

    override suspend fun probe(): ChannelStatus {
        val hasRoot = withContext(Dispatchers.IO) { RootShell.hasRoot() }
        if (!hasRoot) {
            return ChannelStatus.Unavailable(method, "未获得 Root 授权（KernelSU/Magisk 未授权本应用）")
        }
        val output = runCli("probe")
        if (findLine(output, PROBE_OK) != null) {
            cliUsable = true
            return ChannelStatus.Available
        }
        // 退化模式：su 可用但 app_process 不可用 → 用 settings 读写能力兜底
        if (withContext(Dispatchers.IO) { settingsProbe() }) {
            cliUsable = false
            Log.i(TAG, "app_process 不可用，Root 通道退化为 settings 模式；原始输出：${output.take(200)}")
            return ChannelStatus.Available
        }
        cliUsable = true
        // CLI 的失败原因形如 "PROBE ERR <reason>"；拿不到就用原始输出兜底
        val reason = findLine(output, PROBE_ERR)?.removePrefix(PROBE_ERR)?.trim()
        return ChannelStatus.Unavailable(
            method,
            reason?.takeIf { it.isNotEmpty() }
                ?: output.takeIf { it.isNotEmpty() }
                ?: "app_process 无输出（可能被 ROM 拒绝，或被重定向到 logcat：adb logcat -s NetPilot）"
        )
    }

    override suspend fun getMode(subId: Int): Int {
        if (cliUsable) {
            val value = parseInt(runCli("getmode", subId.toString()), MODE)
            if (value >= 0) return value
        }
        return withContext(Dispatchers.IO) { settingsGetMode(subId) }
    }

    override suspend fun setMode(subId: Int, mode: NetworkMode): Boolean {
        if (cliUsable) {
            val viaCli = parseBool(runCli("setmode", subId.toString(), mode.value.toString()), SETMOD)
            if (viaCli) return true
        }
        val viaSettings = withContext(Dispatchers.IO) { settingsSetMode(subId, mode.value) }
        if (viaSettings) {
            Log.i(TAG, "setMode 走 settings 兜底：subId=$subId mode=${mode.value}")
        }
        return viaSettings
    }

    override suspend fun getDefaultSlot(): Int {
        if (cliUsable) {
            val slot = parseInt(runCli("getslot"), SLOT)
            if (slot >= 0) return slot
        }
        return withContext(Dispatchers.IO) { settingsGetDefaultSlot() }
    }

    override suspend fun setDefaultSlot(slot: Int): Boolean {
        if (cliUsable) {
            val viaCli = parseBool(runCli("setslot", slot.toString()), SETSLOT)
            if (viaCli) return true
        }
        return withContext(Dispatchers.IO) { settingsSetDefaultSlot(slot) }
    }

    override suspend fun activeSlots(): List<Pair<Int, Int>> {
        if (cliUsable) {
            val payload = findLine(runCli("slots"), SLOTS)?.removePrefix(SLOTS)?.trim().orEmpty()
            if (payload.isNotEmpty()) {
                val parsed = payload.split(',').mapNotNull { token ->
                    val parts = token.trim().split(':')
                    val slot = parts.getOrNull(0)?.toIntOrNull()
                    val subId = parts.getOrNull(1)?.toIntOrNull()
                    if (slot != null && subId != null) slot to subId else null
                }
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return withContext(Dispatchers.IO) {
            runCatching { SubscriptionSwitcher.activeSlotList() }.getOrDefault(emptyList())
        }
    }

    override fun destroy() {
        // 每次调用都是独立的 app_process，无常驻资源需要释放
    }

    // ------------------------------------------------------------------ app_process 路径

    /** 组装并执行一次特权 CLI 调用，返回 trim 过的 stdout。 */
    private suspend fun runCli(vararg args: String): String = withContext(Dispatchers.IO) {
        // packageCodePath 含 "~" 等字符，必须让 RootShell.quote 用单引号包住
        val command = buildString {
            append("CLASSPATH=").append(RootShell.quote(listOf(context.packageCodePath)))
            append(' ').append(APP_PROCESS)
            append(' ').append(APP_PROCESS_BIN)
            append(' ').append(MAIN_CLASS)
            if (args.isNotEmpty()) {
                append(' ').append(RootShell.quote(args.toList()))
            }
        }
        val result = RootShell.exec(command)
        if (result.timedOut) {
            Log.w(TAG, "cli timed out: ${args.joinToString(" ")}")
        }
        Log.d(
            TAG,
            "cli [${args.joinToString(" ")}] code=${result.code} " +
                "out=${result.stdout.trim()} err=${result.stderr.trim()}"
        )
        result.stdout.trim()
    }

    /** 取第一行以 [prefix] 开头的内容；找不到返回 null（前面的 su 警告不影响解析）。 */
    private fun findLine(output: String, prefix: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(prefix) }

    private fun parseInt(output: String, prefix: String): Int =
        findLine(output, prefix)?.removePrefix(prefix)?.trim()?.toIntOrNull() ?: -1

    private fun parseBool(output: String, prefix: String): Boolean =
        findLine(output, prefix)?.removePrefix(prefix)?.trim()?.equals("true", ignoreCase = true) ?: false

    // ------------------------------------------------------------------ settings 兜底路径

    /** `preferred_network_mode` 的键后缀：卡槽 0 用不带后缀的键，卡槽 N 用后缀 N。 */
    private fun slotSuffix(subId: Int): String {
        if (subId < 0) return ""
        val slot = runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
        return if (slot > 0) slot.toString() else ""
    }

    /** `settings get` 能读回一个整数，就认为 settings 兜底可用。 */
    private fun settingsProbe(): Boolean {
        val result = RootShell.exec("settings get global preferred_network_mode")
        return !result.timedOut && result.stdout.trim().toIntOrNull() != null
    }

    private fun settingsGetMode(subId: Int): Int {
        val suffix = slotSuffix(subId)
        val scoped = RootShell.exec("settings get global preferred_network_mode$suffix").stdout.trim()
        scoped.toIntOrNull()?.let { return it }
        if (suffix.isNotEmpty()) {
            // 少数 ROM 双卡都只认不带后缀的键
            val shared = RootShell.exec("settings get global preferred_network_mode").stdout.trim()
            return shared.toIntOrNull() ?: -1
        }
        return -1
    }

    /**
     * 写入后用 `settings get` 回读校验，回读不一致视为失败（通道会被上层记为不可用）。
     *
     * 回读校验可以被用户关掉（设置 → 系统兼容性 → 写入后回读校验，键 `np_verify_write`）：
     * 少数定制 ROM 写完 settings 之后回读拿到的仍是旧值（写入被电话进程忽略、
     * 或 ContentObserver 异步回写），校验就会把「其实已经写进去」误判成失败。
     * 关掉之后退化为「settings put 执行完即视为成功」，代价是失败不再被拦住。
     */
    private fun settingsSetMode(subId: Int, modeValue: Int): Boolean {
        val verify = WriteVerification.enabled(context)
        if (!verify) Log.i(TAG, "回读校验已关闭：settings put 写一次即视为成功")
        val suffix = slotSuffix(subId)
        RootShell.exec("settings put global preferred_network_mode$suffix $modeValue")
        if (!verify) return true
        if (settingsGetMode(subId) == modeValue) return true
        if (suffix.isNotEmpty()) {
            RootShell.exec("settings put global preferred_network_mode $modeValue")
            if (settingsGetMode(-1) == modeValue) return true
        }
        return false
    }

    private fun settingsGetDefaultSlot(): Int {
        val subId = RootShell.exec("settings get global multi_sim_data_call").stdout.trim().toIntOrNull()
            ?: return -1
        return runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
    }

    private fun settingsSetDefaultSlot(slot: Int): Boolean {
        val subId = runCatching { SubscriptionSwitcher.resolveSubIdBySlot(slot) }.getOrDefault(-1)
        if (subId < 0) return false
        RootShell.exec("settings put global multi_sim_data_call $subId")
        // 与 [settingsSetMode] 共用同一个开关：同一次写入行为要么都校验、要么都不校验，
        // 分成两个开关只会让用户困惑（而且设置页只暴露一个）。
        if (!WriteVerification.enabled(context)) return true
        val readBack = RootShell.exec("settings get global multi_sim_data_call").stdout.trim().toIntOrNull()
        return readBack == subId
    }

    private companion object {
        const val TAG = "NetPilot"
        const val APP_PROCESS = "app_process"
        const val APP_PROCESS_BIN = "/system/bin"
        const val MAIN_CLASS = "com.katiusu.netpilot.core.priv.PrivilegedCli"

        const val PROBE_OK = "PROBE OK"
        const val PROBE_ERR = "PROBE ERR"
        const val MODE = "MODE"
        const val SETMOD = "SETMOD"
        const val SLOT = "SLOT"
        const val SETSLOT = "SETSLOT"
        const val SLOTS = "SLOTS"
    }
}
