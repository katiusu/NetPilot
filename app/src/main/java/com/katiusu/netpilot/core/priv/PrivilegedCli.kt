package com.katiusu.netpilot.core.priv

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 特权命令行入口，供 root 的 `app_process` 直接启动（Shizuku 通道不走这里，走 binder）：
 *
 * ```
 * CLASSPATH=/data/app/~~xxx/base.apk app_process /system/bin \
 *     com.katiusu.netpilot.core.priv.PrivilegedCli getmode 1
 * ```
 *
 * 契约：**结果只通过 stdout 单行输出**，然后 `System.exit(0)`：
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
        val line = try {
            dispatch(args)
        } catch (t: Throwable) {
            Log.e(TAG, "PrivilegedCli fatal", t)
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
        val command = args.firstOrNull()?.trim()?.lowercase() ?: return "ERR unknown"
        val second = args.getOrNull(1)?.trim()
        return when (command) {
            "getmode" -> {
                val subId = second?.toIntOrNull() ?: return "MODE -1"
                "MODE ${TelephonyReflection.getCurrentNetworkMode(subId, "cli")}"
            }
            "setmode" -> {
                val subId = second?.toIntOrNull() ?: return "SETMOD false"
                val modeValue = args.getOrNull(2)?.trim()?.toIntOrNull() ?: return "SETMOD false"
                "SETMOD ${setModeWithFallback(subId, modeValue)}"
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
        if (TelephonyReflection.setNetworkMode(subId, modeValue, "cli")) return true
        if (writeSettings("preferred_network_mode", modeValue)) return true
        if (SubscriptionSwitcher.activeSlotList().size > 1 &&
            writeSettings("preferred_network_mode1", modeValue)
        ) {
            return true
        }
        Log.w(TAG, "setmode($subId, $modeValue): all strategies failed")
        return false
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
        ok
    } catch (e: Throwable) {
        Log.w(TAG, "settings put global $key $value failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
