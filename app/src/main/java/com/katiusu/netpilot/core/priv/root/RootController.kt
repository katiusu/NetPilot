package com.katiusu.netpilot.core.priv.root

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.mode.NetworkModeBitmaskMapper
import com.katiusu.netpilot.core.priv.AuthStore
import com.katiusu.netpilot.core.priv.ChannelStatus
import com.katiusu.netpilot.core.priv.ControlMethod
import com.katiusu.netpilot.core.priv.NetworkControlChannel
import com.katiusu.netpilot.core.priv.RootShell
import com.katiusu.netpilot.core.priv.SubscriptionSwitcher
import com.katiusu.netpilot.core.priv.WriteVerification
import com.katiusu.netpilot.core.priv.WriteDiag
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
            // 只有 setmode 才带详细诊断标志：getMode 每个采样周期都会调，
            // 给它带上会让日志页被逐轮淹没，也平白多出跨进程输出。
            val cliArgs = buildList {
                add("setmode")
                add(subId.toString())
                add(mode.value.toString())
                if (WriteDiag.isVerbose) add(WriteDiag.CLI_VERBOSE)
            }
            val viaCli = parseBool(runCli(*cliArgs.toTypedArray()), SETMOD)
            if (viaCli) {
                WriteDiag.always("Root 通道：app_process 子进程返回 SETMOD true（subId=$subId mode=${mode.value}）")
                return true
            }
            WriteDiag.detail("Root 通道：app_process 未返回 true，回落 settings 兜底")
        }
        // 1.5.0：ITelephony 走不通（或 app_process 不可用）时，先写**权威存储**
        // （TelephonyProvider 的 siminfo.allowed_network_types），再退到 settings。
        // 理由见 PrivilegedCli.writeAuthStore：settings 里的 preferred_network_mode 是遗留兼容
        // 字段，写完回读必然一致，根本发现不了「制式没变」。
        val viaAuthStore = withContext(Dispatchers.IO) { authStoreSetMode(subId, mode.value) }
        if (viaAuthStore) {
            WriteDiag.always("Root 通道：已写入权威存储 siminfo.allowed_network_types（subId=$subId mode=${mode.value}）")
            return true
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
        // 子进程没有 LogStore 上下文，它的详细诊断只能由这里逐行搬回应用内日志页。
        // 只认 DIAG 前缀：MODE/SETMOD/SLOT 那些结果行是给解析用的，不进日志。
        for (line in result.stdout.lineSequence()) {
            if (line.trim().startsWith(WriteDiag.CLI_PREFIX)) WriteDiag.forwardCliLine(line)
        }
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
        WriteDiag.detail("settings 兜底：写入键 global preferred_network_mode$suffix = $modeValue（回读校验=$verify）")
        RootShell.exec("settings put global preferred_network_mode$suffix $modeValue")
        if (!verify) {
            WriteDiag.always("settings 兜底：回读校验已关闭，settings put 执行完即视为成功（未验证 modem 是否接受）")
            return true
        }
        val readBack = settingsGetMode(subId)
        if (readBack == modeValue) {
            // 为什么要把这句写进日志：这一条「成功」只证明 SettingsProvider 存下了这个值，
            // 完全不能证明 modem 换了制式 —— 这正是「回读通过却切不动」最常见的误判来源。
            WriteDiag.always(
                "settings 兜底：回读一致（global preferred_network_mode$suffix 读回 $readBack）。" +
                    "注意这只证明设置对象存下了该值，不等于调制解调器已接受"
            )
            return true
        }
        WriteDiag.detail("settings 兜底：回读不一致，键后缀='$suffix' 读回=$readBack 期望=$modeValue")
        if (suffix.isNotEmpty()) {
            RootShell.exec("settings put global preferred_network_mode $modeValue")
            val readBackNoSuffix = settingsGetMode(-1)
            if (readBackNoSuffix == modeValue) {
                WriteDiag.always("settings 兜底：退回无后缀键 preferred_network_mode 后回读一致（读回 $readBackNoSuffix）")
                return true
            }
            WriteDiag.detail("settings 兜底：无后缀键回读仍不一致（读回=$readBackNoSuffix 期望=$modeValue）")
        }
        WriteDiag.warn("settings 兜底写入失败：回读始终拿不到 $modeValue（subId=$subId 键后缀='$suffix'）")
        return false
    }

    /**
     * 走 `su` 执行 `content update` 写权威存储，并做写后回读。
     *
     * 为什么放在这里而不是应用进程：这一列在 `siminfo` 里，写它需要特权身份；
     * 应用进程既没有权限、也拿不到 provider（见 [AuthStore] 的注释）。
     */
    private suspend fun authStoreSetMode(subId: Int, modeValue: Int): Boolean {
        val networkTypes = NetworkModeBitmaskMapper.platform.toBitmask(modeValue) ?: run {
            WriteDiag.warn(
                "Root 通道：模式 $modeValue 不在本机位掩码表内（表内 0..${NetworkModeBitmaskMapper.MAX_NETWORK_MODE}），" +
                    "跳过权威存储写入"
            )
            return false
        }
        WriteDiag.detail("权威存储：目标 subId=$subId mode=$modeValue -> 位掩码=$networkTypes；开始 content update")
        val result = RootShell.exec(AuthStore.updateCommand(networkTypes, subId))
        // 详细日志：把退出码与两路原始输出都留下。「命令拼错」「provider 拒绝」「列不存在」
        // 三种原因在上一层的分类里分别落到 Failed/Denied/Unavailable，但具体是哪一句，
        // 只有原文说得清。
        WriteDiag.detail(
            "权威存储更新原始结果：exit=${result.code} " +
                "stdout=${result.stdout.trim().take(240)} stderr=${result.stderr.trim().take(240)}"
        )
        val updated = AuthStore.classifyUpdate(result.code, result.stdout, result.stderr)
        if (updated !is AuthStore.Write.Ok) {
            WriteDiag.warn("权威存储写入未成功：${AuthStore.describeWrite(updated)}")
            return false
        }
        return when (val back = readAuthStore(subId)) {
            is AuthStore.Read.Value -> {
                val matched = back.networkTypes == networkTypes
                WriteDiag.always(
                    "权威存储回读：${back.networkTypes}（期望 $networkTypes）-> ${if (matched) "一致" else "不一致"}；" +
                        "注意这只说明权威存储里的值已更新，不等于调制解调器已接受"
                )
                matched
            }
            else -> {
                WriteDiag.warn("权威存储写入后无法回读确认：${AuthStore.describeRead(back)}")
                false
            }
        }
    }

    override suspend fun readAuthStore(subId: Int): AuthStore.Read = withContext(Dispatchers.IO) {
        if (!RootShell.hasRoot()) return@withContext AuthStore.Read.Unavailable("未获得 Root 授权")
        var last: AuthStore.Read = AuthStore.Read.Unavailable("没有可用的列")
        for (column in AuthStore.CANDIDATE_COLUMNS) {
            WriteDiag.detail("权威存储读取：subId=$subId 试探列 $column")
            val result = RootShell.exec(AuthStore.queryCommand(column, subId))
            WriteDiag.detail(
                "权威存储读取原始结果：列=$column exit=${result.code} " +
                    "stdout=${result.stdout.trim().take(240)} stderr=${result.stderr.trim().take(240)}"
            )
            val read = AuthStore.classifyQuery(result.code, result.stdout, result.stderr, column)
            if (read is AuthStore.Read.Value || read is AuthStore.Read.Unset) return@withContext read
            last = read
        }
        // 1.5.1：行没查到时不收摊，再枚举一次整张表 ——「没有这个 subId 的行」这句话本身没法分析，
        // 必须同时知道表里现在有哪些行、以及框架给的 subId 候选是哪些（很常见的一种情况是
        // 传进来的 subId 就是 -1：默认数据卡还没定）。
        if (last is AuthStore.Read.NoRow) return@withContext explainNoRow(subId)
        last
    }

    /**
     * `siminfo` 表里没有目标 subId 时，把「表里到底有什么」读回来再给结论。
     *
     * 只在**读**路径做这件事：写路径与写后回读必须严格盯着目标 subId，
     * 拿别的行的值当成功就是造假 —— 所以这里也绝不改写目标，只把原因讲清楚。
     */
    private fun explainNoRow(subId: Int): AuthStore.Read {
        val result = RootShell.exec(AuthStore.listCommand())
        WriteDiag.detail(
            "权威存储枚举原始结果：exit=${result.code} " +
                "stdout=${result.stdout.trim().take(480)} stderr=${result.stderr.trim().take(240)}"
        )
        val rows = AuthStore.parseSimInfoRows(result.stdout)
        val candidates = runCatching { AuthStore.candidateSubIds() }.getOrDefault(emptyList())
        val detail = if (rows.isEmpty()) {
            "sub_id=$subId 在 siminfo 表里没有行，整张表现在是空的" +
                "（本机没有插卡，或 TelephonyProvider 还没登记任何卡）；框架候选 subId=$candidates"
        } else {
            "sub_id=$subId 在 siminfo 表里没有行；表里现有 ${rows.size} 行：" +
                "${AuthStore.describeRows(rows)}；框架候选 subId=$candidates"
        }
        WriteDiag.detail("权威存储读不到的确切原因：$detail")
        return AuthStore.Read.NoRow(detail)
    }

    override suspend fun writeAuthStore(subId: Int, networkTypes: Long): AuthStore.Write =
        withContext(Dispatchers.IO) {
            if (!RootShell.hasRoot()) return@withContext AuthStore.Write.Denied("未获得 Root 授权")
            val result = RootShell.exec(AuthStore.updateCommand(networkTypes, subId))
            WriteDiag.detail(
                "权威存储写入原始结果：exit=${result.code} stdout=${result.stdout.trim().take(240)} " +
                    "stderr=${result.stderr.trim().take(240)}"
            )
            val updated = AuthStore.classifyUpdate(result.code, result.stdout, result.stderr)
            if (updated !is AuthStore.Write.Ok) return@withContext updated
            when (val back = readAuthStore(subId)) {
                is AuthStore.Read.Value ->
                    if (back.networkTypes == networkTypes) AuthStore.Write.Ok
                    else AuthStore.Write.Mismatch(back.networkTypes, networkTypes)
                else -> AuthStore.Write.NotVerified(AuthStore.describeRead(back))
            }
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
