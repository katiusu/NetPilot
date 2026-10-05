package com.katiusu.netpilot.core.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动更新模组：查「我们的库」里最新一个 Release 的版本号。
 *
 * 写法与同作者的 `miuix-gui-example` 保持一致（两边的 UpdateChecker 要能对着看），
 * 只把仓库地址换成 NetPilot 自己的库 [REPO]。
 *
 * 边界，刻意为之：
 * - **只查、只提示**，不静默下载也不静默安装 —— 点「前往更新」交给系统浏览器打开 Release
 *   页面，由用户自己决定。这样既不需要 `REQUEST_INSTALL_PACKAGES`，也不会在后台做任何事；
 * - **没有后台轮询**：只在应用启动（受「自动检查更新」开关控制）或用户主动点「检查更新」时
 *   发一次请求。后台唤醒正是这个工程这一轮要削掉的东西，新增模组不能反过来往里加。
 *
 * 任何失败（断网、超时、GitHub 限流、返回体结构变了）都必须收敛成 [Result.failure]，
 * 绝不抛给 UI。
 */
object UpdateChecker {

    /** 「我们的库」：NetPilot 的发布页。 */
    private const val REPO = "katiusu/NetPilot"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_URL = "https://github.com/$REPO/releases"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        val releaseName: String,
        val releaseNotes: String,
        val hasUpdate: Boolean,
    )

    /**
     * 查一次最新 Release。
     *
     * 只应在「界面可见」时调用：启动检查与用户主动检查各一次。断网/超时/限流都会走
     * [Result.failure]。
     */
    suspend fun checkForUpdate(context: Context): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val currentVersion = currentVersion(context)
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "GET"
                // GitHub 对匿名请求要求带 UA；带上版本号也方便在服务端区分来源。
                conn.setRequestProperty("User-Agent", context.packageName + "/" + currentVersion)
                conn.setRequestProperty("Accept", "application/vnd.github+json")

                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP $code")
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "")
                val latestVersion = tag.removePrefix("v").removePrefix("V").ifEmpty { currentVersion }
                UpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseUrl = json.optString("html_url", RELEASES_URL).ifEmpty { RELEASES_URL },
                    releaseName = json.optString("name", latestVersion),
                    releaseNotes = json.optString("body", ""),
                    hasUpdate = isNewerVersion(latestVersion, currentVersion),
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 当前安装版本的 `versionName`；读不到时退化成 "1.0"，绝不抛。 */
    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    /**
     * [latest] 是否比 [current] 新。
     *
     * 按 `[.\-+]` 分段逐段比数字，逐段相等则视为**不是**新版本（同版本不提示更新）。
     * 非数字段按 0 处理 —— 版本号里出现 `beta` 之类后缀时只会保守地判「不更新」。
     */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.trim().removePrefix("v").removePrefix("V")
        val c = current.trim().removePrefix("v").removePrefix("V")
        if (l == c) return false
        val lp = l.split(Regex("[.\\-+]"))
        val cp = c.split(Regex("[.\\-+]"))
        for (i in 0 until maxOf(lp.size, cp.size)) {
            val lv = lp.getOrNull(i)?.toIntOrNull() ?: 0
            val cv = cp.getOrNull(i)?.toIntOrNull() ?: 0
            if (lv != cv) return lv > cv
        }
        return false
    }
}
