package com.katiusu.netpilot.core.priv.shizuku

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/**
 * 回收「上一次运行留下来的」Shizuku 用户服务进程。
 *
 * 背景：Shizuku 的用户服务是 app_process 拉起的独立进程，进程名是 `<包名>:np_service`，
 * 权限是 root/shell。客户端（本应用进程）被系统杀掉时来不及执行
 * `unbindUserService(remove = true)`，这个进程就变成 PPID=1 的孤儿一直驻留，
 * 每个约 40 MB —— 实测一台机器上累积了 4 个（约 180 MB）。这里在绑定成功后清扫一次。
 *
 * 安全约束：
 * - 只认「cmdline 里含 `<包名>:np_service` 整串」的进程 —— 主进程的 cmdline 是
 *   `<包名>`，不含该后缀，绝不会被误伤（comm 只有 15 字符会被截断，不能用）；
 * - 永远不会杀自己（[Process.myPid]），也不会碰 pid <= 1；
 * - 全程 runCatching：读不到 /proc、selinux 拒绝、权限不足都只是「没清到」，不影响通道可用性。
 */
internal object StaleProcessPruner {

    private const val TAG = "NetPilot"
    private const val PROCESS_SUFFIX = ":np_service"

    /**
     * @param packageName 宿主应用包名
     * @return 实际杀掉的进程数（0 表示没有可回收的，或没权限）
     */
    fun prune(packageName: String): Int {
        val self = Process.myPid()
        val marker = packageName + PROCESS_SUFFIX
        val candidates = runCatching { processIds(marker) }.getOrDefault(emptyList())
        var killed = 0
        for (pid in candidates) {
            if (pid == self || pid <= 1) continue
            val ok = runCatching {
                Os.kill(pid, OsConstants.SIGKILL)
                true
            }.getOrDefault(false)
            if (ok) killed++
        }
        if (killed > 0) Log.i(TAG, "pruned $killed stale$PROCESS_SUFFIX process(es)")
        return killed
    }

    /** 扫 /proc，返回 cmdline 里含 [marker] 的 pid 列表。 */
    private fun processIds(marker: String): List<Int> {
        val dirs = File("/proc").listFiles { file ->
            file.isDirectory && file.name.firstOrNull()?.isDigit() == true
        } ?: return emptyList()
        val result = ArrayList<Int>(4)
        for (dir in dirs) {
            val cmdline = runCatching {
                File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (!cmdline.contains(marker)) continue
            dir.name.toIntOrNull()?.let { result += it }
        }
        return result
    }
}
