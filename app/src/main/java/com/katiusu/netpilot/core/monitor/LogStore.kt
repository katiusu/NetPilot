package com.katiusu.netpilot.core.monitor

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    /**
     * 进程内单调递增的序号（1.5.2 新增），**只服务于界面**。
     *
     * 日志页用它当 `items` 的 key：没有 key 时「在头部插入一条」会让所有可见行的索引
     * 整体位移，Compose 只能把可见行全部重建（1.5.1 改成倒序显示后，这件事被放大成
     * 「每来一条日志就重排一次界面」）。它不参与任何判定，也不落盘 —— 重启后重新编号。
     */
    val seq: Long = 0L,
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

    /**
     * 落盘节流窗口。
     *
     * 为什么从 4 秒放宽到 30 秒：日志活跃时每 [PERSIST_INTERVAL_MS] 就要把最近
     * [PERSIST_ENTRIES] 条拼成一个约 20 KB 的 JSON 串、再整份写回 SharedPreferences。
     * 4 秒意味着理论上每小时最多 900 次「序列化 + 整文件重写 + fsync」，是应用里最稳定的
     * 一块闪存写入来源。落盘的那份**只在下次进程启动时才被读**（日志页读的是内存里的
     * [buffer]），所以放宽窗口对界面零影响，代价只是「进程被系统直接杀掉时最多丢 30 秒
     * 日志」—— ERROR 级别的日志仍然绕过节流立刻落盘（见 [log]）。
     */
    private const val PERSIST_INTERVAL_MS = 30_000L

    /** 阻塞式落盘（用户点「清空日志」用）的最长等待，任何情况下都不卡死调用线程。 */
    private const val WRITE_TIMEOUT_MS = 2_000L

    /** 单条消息最长字符数，避免异常堆栈这类超长文本长期占内存（截断而非丢弃）。 */
    private const val MAX_MESSAGE_CHARS = 2_000

    private val buffer = mutableStateListOf<LogEntry>()

    /** [LogEntry.seq] 的来源；[log] 会被多个线程调用（主线程、监控 IO 线程），所以用原子量。 */
    private val seqCounter = AtomicLong(0L)

    /**
     * 单线程落盘器（守护线程）。
     *
     * 为什么必须挪出调用线程：[log] 的调用方既有 Compose 主线程（界面动作），也有监控循环
     * 的 IO 线程。原实现直接在调用线程上拼 120 条 JSON 再 `.apply()`，等于把「序列化 +
     * 写整份 SharedPreferences」的 CPU 成本摊到主线程上。用单线程执行器还能顺带保证
     * 多次落盘不会并发写同一个文件。
     */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NetPilot-LogWriter").apply { isDaemon = true }
    }

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
                    // 落盘格式里没有 seq：恢复时按读到的顺序重新编号，保证 key 唯一。
                    seq = seqCounter.incrementAndGet(),
                )
            }
            buffer.clear()
            buffer.addAll(restored)
        }
    }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(
            System.currentTimeMillis(),
            level,
            tag,
            message.take(MAX_MESSAGE_CHARS),
            seqCounter.incrementAndGet(),
        )
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
        // 用户主动清空：这一次必须**同步**写完再返回，否则「清完立刻杀进程」会把日志留下。
        persist(force = true, blocking = true)
    }

    /**
     * 强制落盘（异步）。
     *
     * 服务/引擎停止时调用，避免丢最后几条。**注意它是异步的**：写盘排在 [writer] 上，
     * 方法返回不代表已经写完。需要「返回即已落盘」的场合走 [clear] 那种阻塞路径。
     */
    fun flush() = maybePersist(force = true)

    private fun maybePersist(force: Boolean) = persist(force = force, blocking = false)

    private fun persist(force: Boolean, blocking: Boolean) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        if (!dirty) return
        lastPersistAt = now
        dirty = false
        // 快照必须在这里取：buffer 是 Compose 的 SnapshotStateList，交给另一个线程延迟读不安全。
        val pending = buffer.takeLast(PERSIST_ENTRIES).toList()
        val task = Runnable { writeEntries(ctx, pending) }
        if (blocking) {
            runCatching { writer.submit(task).get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } else {
            runCatching { writer.execute(task) }
        }
    }

    private fun writeEntries(ctx: Context, entries: List<LogEntry>) {
        runCatching {
            val array = JSONArray()
            // 只落盘最近的 PERSIST_ENTRIES 条；内存 buffer 不变。
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("t", entry.timeMs)
                        .put("l", entry.level.name)
                        .put("g", entry.tag)
                        .put("m", entry.message)
                )
            }
            // 用 commit() 而不是 apply()：这里本来就在后台线程上，没有卡主线程的风险，
            // 而 commit() 会返回成功与否 —— 失败就重新标脏，下一次日志再试一遍。
            val ok = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENTRIES, array.toString()).commit()
            if (!ok) dirty = true
        }.onFailure { dirty = true }
    }
}
