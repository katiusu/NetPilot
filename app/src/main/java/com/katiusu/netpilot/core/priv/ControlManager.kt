package com.katiusu.netpilot.core.priv

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.priv.root.RootController
import com.katiusu.netpilot.core.priv.shizuku.ShizukuController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 特权通道选择器：**Root 优先，Shizuku 兜底**，都不行返回 null。
 *
 * - 结果缓存，`forceRefresh = true` 强制重探；缓存通道掉线（su 被撤销 / Shizuku 退出）会自动重探；
 * - 用 [Mutex] 串行化，多个页面同时 acquire 也只会探测一次；
 * - 两条通道的失败原因都留在 [status] / [statuses] 里供 UI 展示
 *   （"没有 root" 和 "Shizuku 没授权" 要区分开，后者用户自己能修）；
 * - 除通道选择外还提供一层**门面 API**（[getMode] / [setMode] / [setDefaultDataSubId] …），
 *   让 `core/NetPilot.kt`、`DataCardEngine`、`MonitorEngine` 不必自己持有 channel。
 */
object ControlManager {

    private const val TAG = "NetPilot"
    private const val SHIZUKU_PERMISSION_CODE = 4210

    /** 单条通道的自述，给 UI 的「通道状态」列表用。 */
    data class ChannelNote(val label: String, val note: String)

    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedChannel: NetworkControlChannel? = null

    @Volatile
    private var cachedMethod: ControlMethod = ControlMethod.NONE

    @Volatile
    private var lastRootStatus: ChannelStatus? = null

    @Volatile
    private var lastShizukuStatus: ChannelStatus? = null

    /** reset() 之后 RootShell 的 su 结论也需要重测。 */
    @Volatile
    private var rootCacheStale = true

    /** 最近一次 [acquire] 的结果。 */
    val activeMethod: ControlMethod get() = cachedMethod

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        // Root 通道的「取活动卡 / 换默认数据卡」走 SubscriptionSwitcher 的反射实现，
        // 它需要 Context 才能优先使用公开 API（否则退回静态扫描，双卡场景精度更差）。
        runCatching { SubscriptionSwitcher.attachContext(app) }
        reset()
    }

    /**
     * 拿到可用通道；`forceRefresh = true` 时忽略缓存重新探测。
     * 注意本方法可能触发 su 探测（最长 5s）与 Shizuku 绑定（最长 15s），必须在协程里调用。
     */
    suspend fun acquire(forceRefresh: Boolean = false): NetworkControlChannel? = mutex.withLock {
        val current = cachedChannel
        if (!forceRefresh && current != null) {
            val alive = withContext(Dispatchers.IO) {
                runCatching { current.isConnected() }.getOrDefault(false)
            }
            if (alive) return@withLock current
            Log.w(TAG, "cached ${current.method} channel is no longer connected; re-probing")
            WriteDiag.always("缓存的 ${current.method} 通道已掉线，重新探测特权通道")
            runCatching { current.destroy() }
            cachedChannel = null
            cachedMethod = ControlMethod.NONE
        }

        // su 结论缓存也在 reset/forceRefresh 时失效
        val refreshRoot = forceRefresh || rootCacheStale
        withContext(Dispatchers.IO) {
            runCatching { RootShell.hasRoot(refresh = refreshRoot) }
        }
        rootCacheStale = false

        // 1) Root
        newRootChannel()?.let { root ->
            val rootStatus = withContext(Dispatchers.IO) { root.probe() }
            lastRootStatus = rootStatus
            if (rootStatus is ChannelStatus.Available) {
                adopt(root)
                WriteDiag.always("已选用特权通道：Root（su）")
                return@withLock root
            }
            runCatching { root.destroy() }
        }

        // 2) Shizuku
        val shizuku = newShizukuChannel()
        if (shizuku == null) {
            lastShizukuStatus = ChannelStatus.Unavailable(
                ControlMethod.SHIZUKU,
                "缺少 ApplicationContext（没有调用 ControlManager.init）"
            )
        } else {
            val shizukuStatus = shizuku.probe()
            lastShizukuStatus = shizukuStatus
            if (shizukuStatus is ChannelStatus.Available) {
                adopt(shizuku)
                WriteDiag.always("已选用特权通道：Shizuku")
                return@withLock shizuku
            }
            runCatching { shizuku.destroy() }
        }

        cachedChannel = null
        cachedMethod = ControlMethod.NONE
        Log.w(TAG, "no usable privileged channel: root=$lastRootStatus shizuku=$lastShizukuStatus")
        WriteDiag.warn(
            "没有可用的特权通道：Root=" + describe(lastRootStatus) +
                "；Shizuku=" + describe(lastShizukuStatus)
        )
        null
    }

    /**
     * `(activeMethod, status)`。
     *
     * 有通道 → `(ROOT|SHIZUKU, Available)`；都没通道 → `(NONE, 最有信息量的失败原因)`，
     * "权限没给"（PermissionDenied）比"通道不存在"更值得优先展示，因为前者用户能自己修。
     */
    suspend fun status(): Pair<ControlMethod, ChannelStatus> {
        val channel = acquire()
        if (channel != null) return channel.method to ChannelStatus.Available

        val status = listOfNotNull(lastRootStatus, lastShizukuStatus)
            .firstOrNull { it is ChannelStatus.PermissionDenied }
            ?: lastRootStatus
            ?: lastShizukuStatus
            ?: ChannelStatus.Unavailable(ControlMethod.NONE, "未检测到可用的特权通道（Root 与 Shizuku 都不可用）")
        return ControlMethod.NONE to status
    }

    fun cached(): NetworkControlChannel? = cachedChannel

    /** 丢弃缓存通道与两次探测结论；下次 [acquire] 会重新探测（含重新检测 su）。 */
    fun reset() {
        runCatching { cachedChannel?.destroy() }
        cachedChannel = null
        cachedMethod = ControlMethod.NONE
        lastRootStatus = null
        lastShizukuStatus = null
        rootCacheStale = true
    }

    // ---------------- 门面 API（给 NetPilot / DataCardEngine / MonitorEngine 直接调） ----------------

    /** 当前通道短名；没有通道时返回"无特权通道"（UI 直接显示，不要 null）。 */
    fun cachedLabel(): String = when (cachedMethod) {
        ControlMethod.ROOT -> "Root"
        ControlMethod.SHIZUKU -> "Shizuku"
        ControlMethod.NONE -> cachedChannel?.label ?: "无特权通道"
    }

    /** [reset] 的别名（门面层叫法）。 */
    fun invalidate() = reset()

    /** 两条通道的自述，用于「为什么不能用」的说明。会触发一次 [acquire]。 */
    suspend fun statuses(): List<ChannelNote> {
        acquire()
        return listOf(
            ChannelNote("Root", describe(lastRootStatus)),
            ChannelNote("Shizuku", describe(lastShizukuStatus)),
        )
    }

    /** 读当前制式（RIL 值），失败 -1。 */
    suspend fun getMode(subId: Int): Int = acquire()?.getMode(subId) ?: -1

    /** 写制式，失败 false。 */
    suspend fun setMode(subId: Int, mode: NetworkMode): Boolean =
        acquire()?.setMode(subId, mode) ?: false

    /**
     * 当前默认数据卡的 subId，失败 -1。
     *
     * 优先用通道（先取默认槽位，再把槽位映射回 subId；特权进程里这个映射最准），
     * 通道不可用时退回本进程的公开 API。
     */
    suspend fun getDefaultDataSubId(): Int {
        val channel = acquire()
        if (channel != null) {
            val slot = channel.getDefaultSlot()
            if (slot >= 0) {
                channel.activeSlots().firstOrNull { it.first == slot }?.let { return it.second }
            }
        }
        val direct = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        if (direct >= 0) return direct
        // 1.5.1：默认数据卡还没定下来时上面会返回 -1，而 -1 拿去查 siminfo 永远只能得到
        // 「没有这个 subId 的行」—— 日志里看起来像 provider 读不出来，其实是目标本身是空的。
        // 退到「活动卡列表 → 默认语音卡」这两个公开来源，并把这次换目标记进详细日志：
        // 目标变了就必须让人看得见，否则「读到了别的卡的值」会变成新的隐形行为。
        val fallback = AuthStore.candidateSubIds().firstOrNull()
        if (fallback != null) {
            WriteDiag.detail("默认数据卡未给出 subId（-1），改用候选卡列表首个 subId=$fallback（活动卡/默认语音卡）")
            return fallback
        }
        return -1
    }

    /**
     * 切换默认数据卡。subId → 槽位优先用通道的活动卡列表，其次用本进程的公开映射。
     */
    suspend fun setDefaultDataSubId(subId: Int): Boolean {
        if (subId < 0) return false
        val channel = acquire() ?: return false
        val slot = channel.activeSlots().firstOrNull { it.second == subId }?.first
            ?: runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
        if (slot < 0) {
            Log.w(TAG, "找不到 subId=$subId 对应的槽位，拒绝切换默认数据卡")
            return false
        }
        return channel.setDefaultSlot(slot)
    }

    /** 发起 Shizuku 授权弹窗。返回 true 表示请求已发出（授权结果由系统回调/重启后生效）。 */
    fun requestShizukuPermission(): Boolean {
        val ctx = contextOrNull() ?: return false
        val controller = (cachedChannel as? ShizukuController) ?: ShizukuController(ctx)
        return try {
            controller.requestPermission(SHIZUKU_PERMISSION_CODE)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "requestShizukuPermission failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 清掉历史遗留的 Shizuku 用户服务孤儿进程（应用启动时调用一次，全静默）。
     *
     * 走 [ShizukuController.pruneOrphanedServices] 而不是 [acquire]：清孤儿的目的是省内存，
     * 不能顺手把通道缓存改成「已绑定」状态，也不能因为网络健康就走不到这一步。
     * Shizuku 不可用时返回 0 —— 这条路径失败不影响任何功能。
     */
    suspend fun pruneOrphanedServices(): Int {
        val ctx = contextOrNull() ?: return 0
        return runCatching { ShizukuController(ctx).pruneOrphanedServices() }.getOrDefault(0)
    }

    // ---------------- 内部 ----------------

    private fun describe(status: ChannelStatus?): String = when (status) {
        null -> "未探测"
        is ChannelStatus.Available -> "可用"
        is ChannelStatus.PermissionDenied -> status.hint
        is ChannelStatus.Unavailable -> status.reason
    }

    /**
     * 取 ApplicationContext。
     *
     * 首选 [init]；万一调用方忘了 init（`core/NetPilot.kt` 的 `install()` 目前只 init 了
     * Prefs/Config/Monitor，没有 init 本类），用 `ActivityThread.currentApplication()` 兜底 ——
     * 主线程起来之后它一定非空，这样通道不会因为「忘了一行 init」而整体失效。
     */
    private fun contextOrNull(): Context? {
        appContext?.let { return it }
        TelephonyReflection.ensureHiddenApiExempted()
        val app = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Throwable) {
            Log.w(TAG, "ActivityThread.currentApplication fallback failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } ?: return null
        val ctx = app.applicationContext ?: app
        appContext = ctx
        return ctx
    }

    private fun newRootChannel(): NetworkControlChannel? =
        contextOrNull()?.let { RootController(it) }

    private fun newShizukuChannel(): NetworkControlChannel? =
        contextOrNull()?.let { ShizukuController(it) }

    private fun adopt(channel: NetworkControlChannel) {
        cachedChannel = channel
        cachedMethod = channel.method
    }
}
