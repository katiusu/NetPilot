package com.katiusu.netpilot.core.priv

import android.util.Log
import com.katiusu.netpilot.core.mode.NetworkModeBitmaskMapper
import java.util.concurrent.TimeUnit

/**
 * 特权命令行入口，供 root 的 `app_process` 直接启动（Shizuku 通道不走这里，走 binder）：
 *
 * ```
 * CLASSPATH=/data/app/~~xxx/base.apk app_process /system/bin \
 *     com.katiusu.netpilot.core.priv.PrivilegedCli getmode 1
 * ```
 *
 * 契约：结果以 stdout 的**单行**给出（前缀见下表），然后 `System.exit(0)`。
 * 详细诊断另写在 `DIAG ` 前缀的行上（见 [WriteDiag]）；父进程按前缀各行解析，互不干扰。
 * 末尾追加 `--verbose` 只影响诊断详略，**不参与命令解析**：
 * ```
 * getmode <subId>        -> MODE <int>          (失败 MODE -1)
 * setmode <subId> <mode> -> SETMOD <true|false>
 * getslot                -> SLOT <int>
 * setslot <slot>         -> SETSLOT <true|false>
 * slots                  -> SLOTS <slot>:<subId>,...
 * probe                  -> PROBE OK | PROBE ERR <reason>
 * 其它                   -> ERR unknown
 * ```
 *
 * 注意：**不要**在这里 `Looper.prepare()` —— app_process 是直接反射调用本类的 main，
 * 没有任何 Android 生命周期，需要 Looper 的 API（如 TelephonyManager 回调）一概不用。
 * 本类同时把结果镜像到 logcat（tag `NetPilot`），方便 stdout 被 ROM 重定向时排查。
 */
object PrivilegedCli {

    private const val TAG = "NetPilot"

    @JvmStatic
    fun main(args: Array<String>) {
        // 子进程没有 Context，读不到用户的「详细诊断日志」设置，
        // 所以详略只能由父进程在命令行上带过来。
        WriteDiag.attachCli(args.any { it == WriteDiag.CLI_VERBOSE })
        val line = try {
            dispatch(args)
        } catch (t: Throwable) {
            Log.e(TAG, "PrivilegedCli fatal", t)
            WriteDiag.warn("cli 致命错误 " + t.javaClass.simpleName + ": " + t.message)
            "ERR " + t.javaClass.simpleName + ": " + t.message
        }
        // 外层再包一层 try：任何情况下都要 exit，不能让 root shell 挂住
        try {
            println(line)
            Log.i(TAG, "CLI_RESULT $line")
            System.out.flush()
        } catch (_: Throwable) {
            // stdout 不可用时无处可写，继续 exit
        }
        System.exit(0)
    }

    private fun dispatch(args: Array<String>): String {
        // 为什么先摘掉：它只是日志开关，混进位置参数会让 getmode/setmode 整体错位。
        val argv = args.filterNot { it == WriteDiag.CLI_VERBOSE }
        val command = argv.firstOrNull()?.trim()?.lowercase() ?: return "ERR unknown"
        val second = argv.getOrNull(1)?.trim()
        return when (command) {
            "getmode" -> {
                val subId = second?.toIntOrNull() ?: return "MODE -1"
                val mode = TelephonyReflection.getCurrentNetworkMode(subId, "cli")
                WriteDiag.detail("cli getmode subId=$subId -> $mode")
                "MODE $mode"
            }
            "setmode" -> {
                val subId = second?.toIntOrNull() ?: return "SETMOD false"
                val modeValue = argv.getOrNull(2)?.trim()?.toIntOrNull() ?: return "SETMOD false"
                val ok = setModeWithFallback(subId, modeValue)
                WriteDiag.always("cli setmode subId=$subId mode=$modeValue -> $ok")
                "SETMOD $ok"
            }
            "getslot" -> "SLOT ${TelephonyReflection.getDefaultDataSlot()}"
            "setslot" -> {
                val slot = second?.toIntOrNull() ?: return "SETSLOT false"
                val subId = SubscriptionSwitcher.resolveSubIdBySlot(slot)
                if (subId < 0) "SETSLOT false" else "SETSLOT ${SubscriptionSwitcher.setDefaultDataSubId(subId)}"
            }
            "slots" -> {
                val pairs = SubscriptionSwitcher.activeSlotList()
                if (pairs.isEmpty()) {
                    "SLOTS"
                } else {
                    "SLOTS " + pairs.joinToString(",") { "${it.first}:${it.second}" }
                }
            }
            "probe" -> TelephonyReflection.probe()?.let { "PROBE ERR $it" } ?: "PROBE OK"
            else -> "ERR unknown"
        }
    }

    /**
     * `setmode`：先走 ITelephony 反射链；失败再用 `settings` 兜底
     * （移植自 Network_Enhance，MIT）。
     *
     * 子进程里起 `settings` 是允许的 —— 不要在 app 进程里做这件事。
     * 兜底写的是 AOSP 的 `preferred_network_mode`（主卡）；双卡场景下再写一份
     * `preferred_network_mode1`（副卡），确保主副卡都被锁到同一个模式。
     */
    private fun setModeWithFallback(subId: Int, modeValue: Int): Boolean {
        WriteDiag.detail("cli 开始切换：先走 ITelephony 三条写入策略")
        if (TelephonyReflection.setNetworkMode(subId, modeValue, "cli")) {
            WriteDiag.always("cli 由 ITelephony 策略完成切换 subId=$subId mode=$modeValue")
            return true
        }
        // 能落到这里说明 ITelephony 三条全 Miss —— 这本身就是「回读通过却切不动」的头号嫌疑，
        // 所以由 always 级别记录，不必开详细模式也能看见。
        // 1.5.0：先写**权威存储**，再退到 settings。顺序为什么是这样 ——
        // `settings put global preferred_network_mode` 写的是遗留兼容字段，写完回读必然一致
        // （自己写自己读），它在结构上就无法回答「制式到底改了没有」；而 TelephonyProvider 的
        // `siminfo.allowed_network_types` 才是 Android 11+ 系统真正读的地方。先写对的地方，
        // 写不进去才退到那个「一定成功但没有保证」的地方。
        if (writeAuthStore(subId, modeValue)) {
            WriteDiag.always(
                "cli ITelephony 全部失败，已写入权威存储 siminfo.allowed_network_types（subId=$subId mode=$modeValue）；" +
                    "是否下发到调制解调器仍取决于电话进程有没有观察这张表"
            )
            return true
        }
        if (writeSettings("preferred_network_mode", modeValue)) {
            WriteDiag.always("cli ITelephony 全部失败，回落 settings put global preferred_network_mode=$modeValue（命令退出码 0）")
            return true
        }
        val slotCount = SubscriptionSwitcher.activeSlotList().size
        WriteDiag.detail("cli 活动卡槽数=$slotCount")
        if (slotCount > 1 && writeSettings("preferred_network_mode1", modeValue)) {
            WriteDiag.always("cli 主卡字段失败，回落写副卡字段 preferred_network_mode1=$modeValue（命令退出码 0）")
            return true
        }
        Log.w(TAG, "setmode($subId, $modeValue): all strategies failed")
        WriteDiag.warn("cli 所有写入策略全部失败 subId=$subId mode=$modeValue")
        return false
    }

    /**
     * 把位掩码写进权威存储，并做写入后回读。
     *
     * `content update` 的退出码只说明命令跑通了，**不代表那一列真的变成了目标值**
     * （行不存在、列不可写、被 provider 忽略都可能返回 0），所以必须回读。
     */
    private fun writeAuthStore(subId: Int, networkMode: Int): Boolean {
        val networkTypes = NetworkModeBitmaskMapper.platform.toBitmask(networkMode) ?: run {
            WriteDiag.warn(
                "cli 模式 $networkMode 不在本机位掩码表内（表内 0..${NetworkModeBitmaskMapper.MAX_NETWORK_MODE}），" +
                    "跳过权威存储写入"
            )
            return false
        }
        // 详细日志：先把「换算成什么掩码、要去写哪一行」讲清楚，再记原始退出码与输出。
        WriteDiag.detail(
            "cli 目标 subId=$subId mode=$networkMode -> 位掩码=$networkTypes；开始 content update siminfo"
        )
        val update = runTool(
            AuthStore.CONTENT_BIN, "update",
            "--uri", AuthStore.CONTENT_URI_STRING,
            "--where", AuthStore.COLUMN_SUB_ID + "=" + subId,
            "--bind", AuthStore.COLUMN_ALLOWED_NETWORK_TYPES + ":l:" + networkTypes
        )
        WriteDiag.always(
            "cli 权威存储写入：content update siminfo." + AuthStore.COLUMN_ALLOWED_NETWORK_TYPES + "=" + networkTypes +
                "（subId=$subId）-> exit=" + update.first + (if (update.second.isEmpty()) "" else " out=" + update.second.take(120))
        )
        if (update.first != 0) {
            WriteDiag.detail(
                "cli 权威存储写入被拒：exit=" + update.first + " 原始输出=" + update.second.take(240)
            )
            return false
        }
        val back = runTool(
            AuthStore.CONTENT_BIN, "query",
            "--uri", AuthStore.CONTENT_URI_STRING,
            "--projection", AuthStore.COLUMN_SUB_ID + ":" + AuthStore.COLUMN_ALLOWED_NETWORK_TYPES,
            "--where", AuthStore.COLUMN_SUB_ID + "=" + subId
        )
        val readBack = AuthStore.parseQueryOutput(back.second, AuthStore.COLUMN_ALLOWED_NETWORK_TYPES)
        WriteDiag.detail(
            "cli 回读原文：exit=" + back.first + " 输出=" + back.second.take(240) + " -> 解析值=" + (readBack?.toString() ?: "解析不出")
        )
        val matched = back.first == 0 && readBack == networkTypes
        WriteDiag.always(
            "cli 权威存储回读：" + (readBack?.toString() ?: "读不到") + "（期望 $networkTypes）-> " +
                (if (matched) "一致" else "不一致") +
                "；注意这只说明权威存储里的值已更新，不等于调制解调器已接受"
        )
        return matched
    }

    /**
     * 起一个系统命令并等它结束，返回 (退出码, 合并后的输出)。
     *
     * 与 [writeSettings] 同一套写法（先读 EOF 再 waitFor，避免管道写满死锁），
     * 抽出来给 `content` 命令复用 —— `content` 没有 API、也没有 SDK 常量，只能走命令行。
     */
    private fun runTool(vararg argv: String): Pair<Int, String> = try {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroy()
        (if (finished) process.exitValue() else -1) to output
    } catch (e: Throwable) {
        WriteDiag.warn("cli " + argv.firstOrNull() + " 抛异常：" + e.javaClass.simpleName + ": " + e.message)
        -1 to (e.javaClass.simpleName + ": " + e.message)
    }

    private fun writeSettings(key: String, value: Int): Boolean = try {
        val process = ProcessBuilder("settings", "put", "global", key, value.toString())
            .redirectErrorStream(true)
            .start()
        // 先读到 EOF（≈ 进程退出），再 waitFor，避免管道写满导致死锁
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        val ok = finished && process.exitValue() == 0
        if (!finished) process.destroy()
        Log.i(TAG, "settings put global $key $value -> ok=$ok out=$output")
        // 退出码 0 只说明 SettingsProvider 收下了这条记录，**不代表 modem 换了制式** ——
        // 这正是回读能通过却切不动制式的机制之一，所以原始退出码必须留在日志里。
        WriteDiag.always("cli settings put global $key $value -> ok=$ok${
            if (output.isEmpty()) "" else " out=$output"
        }")
        ok
    } catch (e: Throwable) {
        Log.w(TAG, "settings put global $key $value failed: ${e.javaClass.simpleName}: ${e.message}")
        WriteDiag.warn("cli settings put global $key $value 抛异常：${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
