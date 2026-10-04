package com.katiusu.netpilot.core.monitor

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun timeText(): String = FORMATTER.get()!!.format(Date(timeMs))

    companion object {
        // SimpleDateFormat 不是线程安全的，按线程缓存实例。
        private val FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        }
    }
}

/**
 * 运行日志环形缓冲。
 *
 * - 最多保留 [MAX_ENTRIES] 条，超出丢弃最旧的
 * - 用 `mutableStateListOf` 承载，Compose 读 [entries] 自动重组
 * - 落盘做了节流：距上次落盘不足 [PERSIST_INTERVAL_MS] 时只标脏，由下一次落盘一并写出
 */
object LogStore {

    private const val PREFS_NAME = "netpilot_logs"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 400

    /**
     * 落盘只写最近这么多条。
     *
     * 内存里仍然保留 [MAX_ENTRIES] 条（日志页看得到全部），但每次落盘都要把条目拼成一个
     * JSON 串再交给 SharedPreferences，400 条时那个串约 40+ KB；日志活跃时每
     * [PERSIST_INTERVAL_MS] 就要拼一次，是应用里最稳定的分配来源（实测 ART sticky GC
     * 每两秒一次）。只落盘最近 120 条把这个串压到约 1/3，同时永久驻留的那份字符串也变小。
     */
    private const val PERSIST_ENTRIES = 120

    private const val PERSIST_INTERVAL_MS = 4_000L

    /** 单条消息最长字符数，避免异常堆栈这类超长文本长期占内存（截断而非丢弃）。 */
    private const val MAX_MESSAGE_CHARS = 2_000

    private val buffer = mutableStateListOf<LogEntry>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastPersistAt = 0L

    @Volatile
    private var dirty = false

    val entries: List<LogEntry> get() = buffer

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    private fun load() {
        val ctx = appContext ?: return
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val restored = ArrayList<LogEntry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                restored += LogEntry(
                    timeMs = obj.optLong("t"),
                    level = runCatching { LogLevel.valueOf(obj.optString("l", "INFO")) }
                        .getOrDefault(LogLevel.INFO),
                    tag = obj.optString("g", ""),
                    message = obj.optString("m", ""),
                )
            }
            buffer.clear()
            buffer.addAll(restored)
        }
    }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message.take(MAX_MESSAGE_CHARS))
        buffer += entry
        while (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
        dirty = true
        maybePersist(force = level == LogLevel.ERROR)
    }

    fun debug(tag: String, message: String) = log(tag, message, LogLevel.DEBUG)
    fun info(tag: String, message: String) = log(tag, message, LogLevel.INFO)
    fun warn(tag: String, message: String) = log(tag, message, LogLevel.WARN)
    fun error(tag: String, message: String) = log(tag, message, LogLevel.ERROR)

    fun snapshot(limit: Int = 200): List<LogEntry> =
        if (limit >= buffer.size) buffer.toList() else buffer.takeLast(limit).toList()

    fun clear() {
        buffer.clear()
        dirty = true
        maybePersist(force = true)
    }

    /** 强制落盘。服务/引擎停止时调用，避免丢最后几条。 */
    fun flush() = maybePersist(force = true)

    private fun maybePersist(force: Boolean) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        if (!dirty) return
        lastPersistAt = now
        dirty = false
        val array = JSONArray()
        // 只落盘最近的 PERSIST_ENTRIES 条；内存 buffer 不变。
        buffer.takeLast(PERSIST_ENTRIES).forEach { entry ->
            array.put(
                JSONObject()
                    .put("t", entry.timeMs)
                    .put("l", entry.level.name)
                    .put("g", entry.tag)
                    .put("m", entry.message)
            )
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
