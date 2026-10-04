package com.katiusu.netpilot.core

import android.content.Context
import com.katiusu.netpilot.core.datacard.DataCardEngine
import com.katiusu.netpilot.core.datacard.DataCardStore
import com.katiusu.netpilot.core.datacard.DataCardStrategy
import com.katiusu.netpilot.core.datacard.SimReader
import com.katiusu.netpilot.core.datacard.SimSlotInfo
import com.katiusu.netpilot.core.keepalive.KeepAliveScheduler
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.monitor.AutoDowngradeEngine
import com.katiusu.netpilot.core.monitor.CarrierInfo
import com.katiusu.netpilot.core.monitor.DowngradeState
import com.katiusu.netpilot.core.monitor.DowngradeThresholds
import com.katiusu.netpilot.core.monitor.LogEntry
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.core.monitor.MonitorEngine
import com.katiusu.netpilot.core.monitor.MonitorSettings
import com.katiusu.netpilot.core.monitor.SignalSnapshot
import com.katiusu.netpilot.core.priv.ControlManager
import com.katiusu.netpilot.prefs.ConfigState
import com.katiusu.netpilot.prefs.PrefsStore

/**
 * NetPilot 的统一门面。
 *
 * 存在的理由有三个：
 *  1. 界面和外部自动化（广播 / Locale 插件 / QS 磁贴）都只跟它打交道，
 *     内核换实现不影响调用方；
 *  2. 「Root 优先 Shizuku 兜底」的选择、日志、错误兜底都在这一层收口，
 *     不需要每个调用点都写一遍 try/catch；
 *  3. Tasker 那类外部调用者拿不到 suspend 上下文，也需要一个同步可用的
 *     `statusText()` 快照。
 *
 * 约定：所有可能失败的操作返回 Boolean 或带单位的字符串，绝不抛异常给调用方。
 */
object NetPilot {

    private const val TAG = "NetPilot"

    // ---------------- 安装 ----------------

    /**
     * 进程启动时调用一次：初始化内核并接线事件。
     * 幂等，重复调用安全。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        PrefsStore.init(app)
        ConfigState.init(app)
        ControlManager.init(app)
        MonitorEngine.init(app)
        NetPilotEvents.onSample = { ctx, snap -> DataCardEngine.onSample(ctx, snap) }
        // 反向门控：网络质量降级的执行者在 monitor 侧，但「当前默认数据卡开没开这条
        // 策略」在 datacard 侧。这里把判定注入 hub，engine 只问结果，不 import datacard，
        // 两个包因此不会成环。关掉策略后已有降级仍会走正常恢复路径，不会被永久锁在 4G。
        NetPilotEvents.qualityDowngradeAllowed = { subId ->
            val policy = DataCardStore.policies(app)[subId]
            policy != null &&
                DataCardStrategy.NETWORK_QUALITY in DataCardEngine.effectiveStrategies(policy)
        }
        NetPilotEvents.onMonitorStateChanged = { running ->
            LogStore.debug(TAG, "监控状态变化：running=$running")
        }
        // 保活心跳：总开关关着就不注册；这里发现关着时顺手把残留闹钟撤掉，
        // 免得「关掉总开关后重启应用」又把它悄悄带回来（用户会以为开关失灵）。
        runCatching {
            if (ServicesGate.enabled(app)) {
                KeepAliveScheduler.schedule(app)
            } else {
                KeepAliveScheduler.cancel(app)
            }
        }.onFailure {
            LogStore.warn(TAG, "保活闹钟初始化失败：${it.message ?: it.javaClass.simpleName}")
        }
        LogStore.info(TAG, "核心已就绪，首选特权通道：${ControlManager.cachedLabel()}")
    }

    // ---------------- 制式 ----------------

    suspend fun currentModeValue(subId: Int): Int = ControlManager.getMode(subId)

    suspend fun currentModeLabel(subId: Int): String = NetworkMode.labelOf(ControlManager.getMode(subId))

    suspend fun currentModeShort(subId: Int): String = NetworkMode.shortLabelOf(ControlManager.getMode(subId))

    /** 按枚举切制式。 */
    suspend fun setMode(subId: Int, mode: NetworkMode): Boolean {
        if (subId < 0) {
            LogStore.error(TAG, "无效的 subId=$subId，拒绝切换制式")
            return false
        }
        val ok = ControlManager.setMode(subId, mode)
        LogStore.log(
            TAG,
            if (ok) "卡 $subId 制式已切换为 ${mode.label}" else "卡 $subId 切换 ${mode.label} 失败",
            if (ok) com.katiusu.netpilot.core.monitor.LogLevel.INFO
            else com.katiusu.netpilot.core.monitor.LogLevel.ERROR,
        )
        return ok
    }

    /** 按裸值切制式（广播 / Locale 插件传进来的都是数字）。 */
    suspend fun setModeByValue(subId: Int, value: Int): Boolean {
        val mode = NetworkMode.fromValue(value)
        if (mode == null) {
            LogStore.error(TAG, "未知的制式值 $value，已忽略")
            return false
        }
        return setMode(subId, mode)
    }

    /**
     * 按预设名切制式。支持：5g / 5g_auto / 5g_only / 4g / 4g_auto / 4g_only /
     * lte_only / 3g / 2g。大小写、连字符、空格都做了归一化，Tasker 里手打不容易失败。
     */
    suspend fun applyPreset(subId: Int, preset: String): Boolean {
        val key = preset.trim().lowercase().replace('-', '_').replace(' ', '_')
        val mode = when (key) {
            "5g", "5g_auto", "nr_auto", "auto_5g" -> NetworkMode.NR_LTE_CDMA_EVDO_GSM_WCDMA
            "5g_only", "nr_only", "only_5g", "sa" -> NetworkMode.NR_ONLY
            "5g_4g", "nr_lte" -> NetworkMode.NR_LTE
            "4g", "4g_auto", "lte_auto", "auto_4g" -> NetworkMode.DEFAULT_DOWNGRADE
            "4g_only", "lte_only", "only_4g", "only_lte" -> NetworkMode.DEFAULT_LOCK_LTE
            "3g", "3g_auto", "wcdma" -> NetworkMode.WCDMA_PREF
            "3g_only", "wcdma_only" -> NetworkMode.WCDMA_ONLY
            "2g", "2g_only", "gsm", "gsm_only" -> NetworkMode.GSM_ONLY
            else -> null
        }
        if (mode == null) {
            LogStore.error(TAG, "未知预设「$preset」，可选：5g/5g_only/4g/4g_only/3g/2g")
            return false
        }
        return setMode(subId, mode)
    }

    /**
     * 游戏模式：锁 LTE 防跳频。
     *
     * 开启时写「仅 4G」，关闭时回到留档的运营商默认制式（拿不到就按 MCC/MNC 推）。
     */
    suspend fun lockLte(context: Context, on: Boolean): Boolean {
        val subId = SimReader.defaultDataSubId().takeIf { it >= 0 }
            ?: run {
                LogStore.error(TAG, "拿不到默认数据卡，无法锁 LTE")
                return false
            }
        return if (on) {
            setMode(subId, NetworkMode.DEFAULT_LOCK_LTE)
        } else {
            // 0 = 跟随运营商（5G 自动）：按 MCC/MNC 推导，而不是硬写某个窄制式。
            val configured = MonitorSettings.thresholds().lockLteMode
            val fallback = CarrierInfo.defaultModeForActiveSubscription(context, subId)
            val value = if (configured > 0) configured else fallback
            val target = NetworkMode.fromValue(value) ?: NetworkMode.NR_LTE_CDMA_EVDO_GSM_WCDMA
            setMode(subId, target)
            true
        }
    }

    // ---------------- 网络质量自动降级 ----------------

    fun autoDowngradeEnabled(): Boolean = ConfigState.bool(MonitorSettings.KEY_ENABLED, MonitorSettings.DEFAULT_ENABLED)

    fun thresholds(): DowngradeThresholds = MonitorSettings.thresholds()

    fun downgradeState(): DowngradeState = MonitorEngine.downgrade.value

    fun snapshot(): SignalSnapshot? = MonitorEngine.snapshot.value

    fun monitorRunning(): Boolean = MonitorEngine.running.value

    /** 开关自动降级；顺带起停前台服务，避免「开关开着但服务没跑」的假状态。 */
    fun setAutoDowngrade(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled && !ServicesGate.enabled(app)) {
            // 总开关关着：用户的意图要落盘，但服务不能起（否则总开关就没意义了）。
            // 等用户在设置里打开总开关时，[setServicesEnabled] 会按这个配置把服务拉起来，
            // 于是不会留下「自动降级显示开启、服务却没跑」的假状态。
            ConfigState.set(MonitorSettings.KEY_ENABLED, true)
            LogStore.info(TAG, "后台服务总开关已关闭，自动降级开关已记录，暂不启动监控服务")
            return
        }
        ConfigState.set(MonitorSettings.KEY_ENABLED, enabled)
        MonitorEngine.setEnabled(app, enabled)
        LogStore.info(TAG, if (enabled) "已开启网络质量自动降级" else "已关闭网络质量自动降级")
    }

    fun toggleAutoDowngrade(context: Context): Boolean {
        val next = !autoDowngradeEnabled()
        setAutoDowngrade(context, next)
        return next
    }

    /** 只起停监控（不改自动降级开关），给「监控」页用。 */
    fun setMonitorOnly(context: Context, enabled: Boolean) {
        // 只拦「启动」：总开关关着时不允许任何入口把服务拉起来；
        // 但「停止」永远放行，否则关掉总开关之后就再也没法停服务了。
        if (enabled && !ServicesGate.enabled(context.applicationContext)) {
            LogStore.warn(TAG, "后台服务总开关已关闭，拒绝启动监控服务")
            return
        }
        MonitorEngine.setEnabled(context.applicationContext, enabled)
    }

    // ---------------- 后台服务总开关 ----------------

    fun servicesEnabled(context: Context): Boolean = ServicesGate.enabled(context)

    /**
     * 后台服务总开关（设置页「后台服务」卡片）。**唯一会真正「全部停下」的入口**：
     * [ServicesGate] 只负责配置读写，服务与闹钟的起停收口在这里 ——
     * 这样 ServicesGate 不必反向依赖 MonitorService，依赖图里不会出现环。
     *
     * 关掉时按「先落配置 → 再停服务 → 最后撤闹钟」的顺序：
     * 服务会在 onDestroy 里读总开关，先落配置它读到的才是「已关闭」，
     * 于是不会再排一个刚被撤掉的重启闹钟。
     */
    fun setServicesEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        ServicesGate.setEnabled(app, enabled)
        if (enabled) {
            KeepAliveScheduler.schedule(app)
            // 总开关本身不代表「要跑监控」。若自动降级开关开着，就把服务拉回来 ——
            // 否则界面显示「开启」而服务没跑，正是本门面一直在避免的假状态。
            if (autoDowngradeEnabled()) MonitorEngine.setEnabled(app, true)
        } else {
            MonitorEngine.setEnabled(app, false)
            KeepAliveScheduler.cancel(app)
        }
    }

    suspend fun sampleNow(context: Context): SignalSnapshot = MonitorEngine.sampleOnce(context)

    fun resetDowngradeState(context: Context) = MonitorEngine.resetState(context)

    // ---------------- 双卡 / 数据卡 ----------------

    fun sims(context: Context): List<SimSlotInfo> = SimReader.sims(context)

    suspend fun defaultDataSubId(): Int = ControlManager.getDefaultDataSubId()

    suspend fun setDefaultDataSubId(context: Context, subId: Int): Boolean {
        if (subId < 0) return false
        DataCardStore.setHomeSubId(context.applicationContext, subId)
        val ok = ControlManager.setDefaultDataSubId(subId)
        LogStore.log(
            TAG,
            if (ok) "默认数据卡已切到 subId $subId" else "切换默认数据卡到 subId $subId 失败",
            if (ok) com.katiusu.netpilot.core.monitor.LogLevel.INFO
            else com.katiusu.netpilot.core.monitor.LogLevel.ERROR,
        )
        return ok
    }

    /** 双卡一键互换：切到「当前不是默认数据卡」的那张。返回切换后的 subId，失败 -1。 */
    suspend fun switchDataSim(context: Context): Int {
        val current = SimReader.defaultDataSubId()
        val other = SimReader.sims(context).firstOrNull { it.subId != current } ?: run {
            LogStore.warn(TAG, "只有一张卡，无法互换数据卡")
            return -1
        }
        return if (setDefaultDataSubId(context, other.subId)) other.subId else -1
    }

    // ---------------- 状态快照（给磁贴 / 通知 / Tasker） ----------------

    /** 单行状态，形如 `5G 自动 · RSRP -92 dBm · 35 ms · 未降级`。 */
    fun statusText(): String {
        val snap = snapshot() ?: return "尚未采样"
        val state = downgradeState()
        return buildString {
            append(snap.networkType)
            append(" · ")
            append(snap.rsrp?.let { "RSRP $it dBm" } ?: "RSRP 未知")
            append(" · ")
            append(snap.pingMs?.let { "$it ms" } ?: "无响应")
            append(" · ")
            append(if (state.active) "已降级" else "未降级")
        }
    }

    fun channelLabel(): String = ControlManager.cachedLabel()

    suspend fun channelSummary(): String =
        ControlManager.statuses().joinToString("；") { "${it.label}: ${it.note}" }

    suspend fun refreshChannel(): String {
        ControlManager.invalidate()
        val ch = ControlManager.acquire(forceRefresh = true)
        return ch?.label ?: "无可用特权通道"
    }

    fun requestShizukuPermission(): Boolean = ControlManager.requestShizukuPermission()

    // ---------------- 日志 ----------------

    fun logs(limit: Int = 200): List<LogEntry> = LogStore.snapshot(limit)

    fun clearLogs() = LogStore.clear()

    /** 直接引 AutoDowngradeEngine 的类型，避免调用方还要 import 一遍。 */
    fun engineForDebug(context: Context): AutoDowngradeEngine = AutoDowngradeEngine(context)
}
