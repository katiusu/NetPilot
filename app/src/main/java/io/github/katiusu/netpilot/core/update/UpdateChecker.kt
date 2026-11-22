package io.github.katiusu.netpilot.core.update

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
     * 取 assets/changelog.md 中「## 版本号」段正文；找不到该版本返回空串。
     *
     * 注意：assets 缺失或读取失败会抛 IOException（不吞），调用方自己 catch。
     *
     * 版本串与 versionName 逐字比较（2.0.0 与 2.0.0-rc.3 是两段），
     * 否则带后缀的版本号会把相邻版本的说明串出来。
     */
    fun currentChangelog(context: Context, version: String): String {
        val changelog = context.assets.open("changelog.md").bufferedReader().use { it.readText() }
        val heading = Regex("""^##[ \t]+v?(\S+)[ \t]*\r?$""", RegexOption.MULTILINE)
        val current = heading.findAll(changelog).firstOrNull { it.groupValues[1] == version }
            ?: return ""
        val start = current.range.last + 1
        val end = heading.find(changelog, start)?.range?.first ?: changelog.length
        return changelog.substring(start, end).trim()
    }

    /**
     * [latest] 是否比 [current] 新。
     *
     * 先按版本分隔符逐段比数字；数字核心相同时，带预发布后缀的一方更旧
     * （2.0.0-rc.3 < 2.0.0），两边都有后缀再逐段比后缀。
     * 以前把 -rc.3 的 3 当成第 5 段数字，于是 rc 被判定比正式版还新：
     * 仓库把 rc 标成正式 Release 时会提示更新，实际是降级。
     */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.trim().removePrefix("v").removePrefix("V")
        val c = current.trim().removePrefix("v").removePrefix("V")
        if (l == c) return false
        val (ln, lpre) = splitVersion(l)
        val (cn, cpre) = splitVersion(c)
        for (i in 0 until maxOf(ln.size, cn.size)) {
            val lv = ln.getOrNull(i) ?: 0
            val cv = cn.getOrNull(i) ?: 0
            if (lv != cv) return lv > cv
        }
        // 数字核心一样：没有后缀的是正式版，比任何预发布版都新。
        if (lpre.isEmpty()) return cpre.isNotEmpty()
        if (cpre.isEmpty()) return false
        for (i in 0 until maxOf(lpre.size, cpre.size)) {
            val lv = lpre.getOrNull(i) ?: return false
            val cv = cpre.getOrNull(i) ?: return true
            val ln2 = lv.toIntOrNull()
            val cn2 = cv.toIntOrNull()
            val cmp = when {
                ln2 != null && cn2 != null -> ln2.compareTo(cn2)
                // SemVer：纯数字段低于字母段。
                ln2 != null -> -1
                cn2 != null -> 1
                else -> lv.compareTo(cv)
            }
            if (cmp != 0) return cmp > 0
        }
        return false
    }

    /** 拆成「数字段」与「预发布后缀段」；后缀从第一个非数字段开始。 */
    private fun splitVersion(version: String): Pair<List<Int>, List<String>> {
        val parts = version.split(Regex("[.+-]"))
        val numbers = ArrayList<Int>(parts.size)
        val suffix = ArrayList<String>()
        for (part in parts) {
            val n = part.toIntOrNull()
            if (n != null && suffix.isEmpty()) numbers += n
            else if (part.isNotEmpty()) suffix += part
        }
        return numbers to suffix
    }
}
