package com.katiusu.netpilot.core.monitor

import com.katiusu.netpilot.core.NetPilotEvents
import android.content.Context
import android.os.PowerManager
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.priv.ControlManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 网络质量降级状态机（假满格 / 信号过差）。
 *
 * 与 Network_Enhance `monitor.sh:390-528` 的 T1/T3/T4/T5 + R1/R2 一一对应：
 * - 未降级 + 需要降级（假满格或信号过差）→ 写入降级模式（默认 9 = LTE/GSM/WCDMA），进入冷却
 * - 冷却期内不做恢复尝试
 * - 冷却结束后，每轮「不需要降级」累加一次恢复计数，累计到阈值就写回原模式
 * - 降级态下连续无网/无响应达阈值 → 立刻整体回退
 *
 * 与原脚本的两处**有意偏离**，都在代码里标注：
 * 1. 原脚本把 `FAKE_5G_ACTIVE` 和计数器放在进程内局部变量，进程被杀后重跑会
 *    卡在「已降级」状态不再恢复（PNM 停在 9，用户会以为丢了 5G）。这里全部
 *    持久化，并且 [selfHeal] 在启动时无条件写回完整制式，保证不会卡死。
 * 2. 原脚本无网回退后仍把 `FAKE_5G_ACTIVE` 留成 1；这里回退即视为本轮结束
 *    （`active = false`），语义更清晰，也避免残留状态。
 */
class AutoDowngradeEngine(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<DowngradeState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<SignalSnapshot?>(null)
    val snapshot: StateFlow<SignalSnapshot?> = _snapshot.asStateFlow()

    private val _judgement = MutableStateFlow<FakeJudgement?>(null)
    val judgement: StateFlow<FakeJudgement?> = _judgement.asStateFlow()

    /**
     * 跑一轮：采样 → 判定 → 执行状态机。返回本轮快照，供 UI 与服务复用。
     *
     * 必须在非主线程调用：内部含 HTTP 探测与特权 shell 等阻塞操作。
     *
     * @param allowProbeSkip 允许在「屏幕关闭 + 当前未处于降级态」时跳过本轮 HTTP 探测。
     *   默认 `false`：界面「立即检测」、监控页快采、Tasker `SAMPLE_NOW` 这类
     *   **用户主动要一次读数**的调用永远真探，读数语义与优化前一致。
     */
    suspend fun tick(allowProbeSkip: Boolean = false): SignalSnapshot {
        val thresholds = MonitorSettings.thresholds()
        val subId = SignalReader.defaultDataSubId()
        // 为什么「屏幕关闭 + 未降级」就够（完整的逐分支核对见 docs/POWER_REPORT.md §3）：
        // 1) 屏幕关闭 ⇒ 没有可见界面在等这个读数；
        // 2) 未处于降级态 ⇒ applyTransition 只在降级分支里读 noResponse，
        //    「本轮没探」在非降级分支里没有任何读者；
        // 3) 信号非强（这一条在 SignalReader.read 内部判定）⇒ judge() 只走
        //    不读 pingMs 的那几条分支。
        // 三条合起来，这一轮的 pingMs 没有任何读者，跳过它不可能改变任何判定。
        val maySkipProbe = allowProbeSkip &&
            !_state.value.active &&
            !isScreenInteractive()
        // 1.5.3：息屏后台轮次不再每轮都探。
        // 原规则只在「信号非强」时跳（那一轮判定不读 pingMs），但信号强的那一轮照样会去
        // ping —— 一次 HTTP 探测要唤醒网络栈，而屏幕关着的时候它的唯一读者是「假满格」
        // 那条判定（弱信号/兜底分支只看 RSRP，见 FakeSignalDetector.judge 与
        // docs/POWER_REPORT.md §3 的逐分支核对）。现在把息屏探测压到最多
        // BACKGROUND_PROBE_BACKOFF_MS 一次：**判定规则一个字没改**，改的是「多久问一次」，
        // 代价是息屏期间「假满格」最晚被推迟这么久才被发现；屏幕一亮、或者界面快采 /
        // 「立即检测」/Tasker 采样这类主动读数（allowProbeSkip = false）立刻回到每轮真探。
        val probeThrottled = maySkipProbe &&
            System.currentTimeMillis() - lastProbeAtMs < BACKGROUND_PROBE_BACKOFF_MS
        val snapshot = SignalReader.read(
            context = context,
            subId = subId,
            pingTarget = thresholds.pingTarget,
            pingTimeoutMs = thresholds.pingTimeoutMs,
            allowProbeSkip = maySkipProbe,
            forceSkipProbe = probeThrottled,
            strongRsrpThreshold = thresholds.rsrpThreshold,
        )
        // 只有真的探了才刷新窗口起点；被跳过（无论哪条理由）的一轮不刷新。
        if (!snapshot.probeSkipped) lastProbeAtMs = System.currentTimeMillis()
        // 只在「跳过/恢复」翻转时记一条日志：既能让用户查得到省电行为，
        // 又不会每轮刷一条（日志落盘本身也是耗电源之一）。
        if (snapshot.probeSkipped != probeSkipLogged) {
            probeSkipLogged = snapshot.probeSkipped
            LogStore.info(
                TAG,
                if (snapshot.probeSkipped) {
                    "屏幕关闭，本轮暂停 HTTP 探测（降级判定不受影响）"
                } else {
                    "恢复 HTTP 探测"
                },
            )
        }
        val judgement = FakeSignalDetector.judge(snapshot, thresholds)
        _snapshot.value = snapshot
        _judgement.value = judgement
        applyTransition(snapshot, judgement, thresholds)
        return snapshot
    }

    /** 上一次是否处于「跳过探测」状态，只用于翻转时记一条日志，避免每轮刷屏。 */
    private var probeSkipLogged = false

    /** 最近一次**真探**的时刻（1.5.3 息屏降频），0 表示还没探过。 */
    private var lastProbeAtMs = 0L

    /** 上一次真正落盘的状态 JSON（1.5.3 脏检查），与当前状态一致时不再写盘。 */
    private var lastPersistedJson: String? = null

    /**
     * 屏幕是否处于交互状态（亮着）。
     *
     * 读它不需要任何权限，也不产生唤醒源。读不到时**保守返回 true**（当作有人在看），
     * 这样任何异常都只会让优化失效，不会让应用做出「以为没人看」的错误判断。
     */
    private fun isScreenInteractive(): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
    }.getOrDefault(true)

    private suspend fun applyTransition(
        snapshot: SignalSnapshot,
        judgement: FakeJudgement,
        thresholds: DowngradeThresholds,
    ) {
        var current = _state.value
        val now = System.currentTimeMillis()
        val subId = snapshot.subId
        // 与原脚本 `net_type == "none"` 等价：制式读数为 0 表示当前没有蜂窝数据。
        val hasCellular = snapshot.rawNetworkType != 0
        // probeSkipped 必须排除：那一轮的 pingMs == null 表示「省电没去探」，
        // 不是「探了没通」。当前能跳过探测的轮次一定满足 !active（见 tick 里的
        // maySkipProbe），根本走不到这一行；这里显式写出来，是为了让「跳过」
        // 不可能被将来的改动误当成一次真的无网回退。
        val noResponse = snapshot.pingMs == null && !snapshot.isWifi && !snapshot.probeSkipped

        if (!current.active) {
            // 每卡门控：当前默认数据卡没有启用「网络质量自动降级」时，本引擎不对它
            // 动手。注意这里只拦「进入降级」——下面的恢复分支照常可达，否则用户中途
            // 关掉策略时，已经降下去的制式就再也写不回来了，会被永久锁在 4G。
            val cardAllowsDowngrade = NetPilotEvents.allowsQualityDowngrade(subId)
            if (!thresholds.enabled || !judgement.fake || !cardAllowsDowngrade) {
                if (thresholds.enabled && judgement.fake && !cardAllowsDowngrade) {
                    LogStore.log(
                        TAG,
                        "本轮${judgement.detail}，但 subId=$subId 这张卡未启用「网络质量自动降级」，跳过",
                        LogLevel.DEBUG,
                    )
                }
                _state.value = current
                return
            }
            val saved = readModeOrMinusOne(subId)
            if (saved == thresholds.downgradeMode) {
                // 已经停在降级目标制式上（用户本来就锁着 4G，或上一轮刚降级成功）。
                // 弱信号规则几乎每轮都会命中，这里不拦住的话就会每个周期都写一次
                // settings：白耗电，还可能撞上系统对 setting 写入的频率限制。
                LogStore.log(
                    TAG,
                    "制式已是 ${thresholds.downgradeMode}（${modeLabel(thresholds.downgradeMode)}），" +
                        "无需重复写入；本轮 ${judgement.detail}",
                    LogLevel.DEBUG,
                )
                _state.value = current
                return
            }
            val ok = writeMode(subId, thresholds.downgradeMode, thresholds)
            current = if (ok) {
                LogStore.log(
                    TAG,
                    "检出${reasonLabel(judgement.reason)}（${judgement.reasons.joinToString("；")}）→ 已写入制式 " +
                        "${thresholds.downgradeMode}（${modeLabel(thresholds.downgradeMode)}）",
                    LogLevel.WARN,
                )
                DowngradeState(
                    active = true,
                    degradedAtMs = now,
                    recoveryCount = 0,
                    noNetFailCount = 0,
                    appliedMode = thresholds.downgradeMode,
                    savedMode = saved,
                    lastEvent = "已降级到 ${modeLabel(thresholds.downgradeMode)}",
                    lastEventAtMs = now,
                )
            } else {
                LogStore.log(
                    TAG,
                    "检出${reasonLabel(judgement.reason)} 但写入制式失败（特权通道不可用？）",
                    LogLevel.ERROR,
                )
                current.copy(
                    lastEvent = "降级失败：无法写入制式",
                    lastEventAtMs = now,
                )
            }
            persist(current)
            _state.value = current
            return
        }

        // ---- 已降级 ----
        if (noResponse || !hasCellular) {
            // 原脚本 R1/R2：降级态下无网要尽快把完整制式还回去，否则会连 3G 都收不到。
            val failCount = current.noNetFailCount + 1
            current = current.copy(noNetFailCount = failCount)
            if (failCount >= thresholds.noNetRollbackCount) {
                LogStore.log(
                    TAG,
                    "降级态下连续 $failCount 轮无网/无响应 → 立即回退完整制式",
                    LogLevel.WARN,
                )
                rollback(subId, current, thresholds, reason = "降级后无网，自动回退")
                return
            }
            persist(current)
            _state.value = current
            return
        }
        if (current.noNetFailCount != 0) {
            current = current.copy(noNetFailCount = 0)
        }

        val cooldownRemainingMs =
            thresholds.cooldownSec * 1000L - (now - current.degradedAtMs)
        if (cooldownRemainingMs > 0L) {
            LogStore.log(
                TAG,
                "降级冷却中，剩余 ${cooldownRemainingMs / 1000} 秒；本轮 ${judgement.detail}",
                LogLevel.DEBUG,
            )
            persist(current)
            _state.value = current
            return
        }

        if (!judgement.fake) {
            val recovered = current.recoveryCount + 1
            current = current.copy(recoveryCount = recovered)
            if (recovered >= thresholds.recoveryCount) {
                restore(subId, current, thresholds)
                return
            }
            LogStore.log(
                TAG,
                "冷却结束且网络正常，恢复计数 $recovered/${thresholds.recoveryCount}",
                LogLevel.INFO,
            )
        } else {
            // 原脚本在 T2 重复检出质量问题时**不**清零恢复计数，这里保持一致：
            // 恢复计数语义是「冷却结束后累计的正常轮数」，不是「连续正常」。
            LogStore.log(TAG, "仍满足降级条件：${judgement.detail}", LogLevel.DEBUG)
        }
        persist(current)
        _state.value = current
    }

    private suspend fun restore(subId: Int, current: DowngradeState, thresholds: DowngradeThresholds) {
        val target = restoreTarget(subId, current, thresholds)
        val ok = writeMode(subId, target, thresholds)
        LogStore.log(
            TAG,
            if (ok) "恢复条件满足，已写回制式 $target（${modeLabel(target)}）"
            else "恢复条件满足但写回制式失败",
            if (ok) LogLevel.INFO else LogLevel.ERROR,
        )
        finish(current, ok, "已恢复正常制式")
    }

    private suspend fun rollback(
        subId: Int,
        current: DowngradeState,
        thresholds: DowngradeThresholds,
        reason: String,
    ) {
        val target = restoreTarget(subId, current, thresholds)
        val ok = writeMode(subId, target, thresholds)
        LogStore.log(
            TAG,
            if (ok) "$reason：已写回制式 $target（${modeLabel(target)}）" else "$reason：写回制式失败",
            if (ok) LogLevel.WARN else LogLevel.ERROR,
        )
        finish(current, ok, reason)
    }

    private fun finish(current: DowngradeState, ok: Boolean, event: String) {
        val next = current.copy(
            active = false,
            degradedAtMs = 0L,
            recoveryCount = 0,
            noNetFailCount = 0,
            appliedMode = -1,
            savedMode = -1,
            lastEvent = if (ok) event else "$event（写入失败）",
            lastEventAtMs = System.currentTimeMillis(),
        )
        persist(next)
        _state.value = next
    }

    /**
     * 恢复（回退）时要写回的目标制式。
     *
     * 顺序：
     * 1. 用户显式配置的「恢复目标制式」——只要不是 0（跟随运营商）就用它；
     * 2. `0`（默认）→ 按当前卡的 MCC/MNC 推导运营商默认值，也就是**「5G 自动」**；
     * 3. 运营商读不到 → 退回降级前保存的制式；
     * 4. 都没有 → 26（NR/LTE/GSM/WCDMA）。
     *
     * 之所以把「跟随运营商」放在 `savedMode` 之前：用户明确要求恢复目标默认是 5G 自动，
     * 而 savedMode 可能只是用户当时手动锁的某个窄制式（例如「仅 4G」）。
     */
    private fun restoreTarget(subId: Int, current: DowngradeState, thresholds: DowngradeThresholds): Int {
        if (thresholds.lockLteMode > 0) return thresholds.lockLteMode
        val carrier = CarrierInfo.defaultModeForActiveSubscription(context, subId)
        if (carrier > 0) return carrier
        return current.savedMode.takeIf { it >= 0 } ?: 26
    }

    private suspend fun readModeOrMinusOne(subId: Int): Int {
        // 1.5.3：改走 ControlManager 的门面。它现在带 5 分钟的短期记忆，所以
        // 「制式是不是已经等于降级目标」这个判断不会每轮都 fork 一个 app_process
        // 去读同一个值（root 通道一次读实测 RSS ≈132 MB）；本引擎自己写完制式时，
        // setMode 也会立刻把那份记忆刷成新值。
        return ControlManager.getMode(subId)
    }

    private suspend fun writeMode(subId: Int, mode: Int, thresholds: DowngradeThresholds): Boolean {
        val channel = ControlManager.acquire() ?: run {
            LogStore.log(TAG, "没有任何可用的特权通道（Root/Shizuku 都不可用）", LogLevel.ERROR)
            return false
        }
        val networkMode = NetworkMode.fromValue(mode) ?: run {
            LogStore.log(TAG, "未知的网络制式值 $mode，拒绝写入", LogLevel.ERROR)
            return false
        }
        return channel.setMode(subId, networkMode)
    }

    /** 成因 → 日志文案。用户要在日志里一眼看出是「假满格」还是「信号过差」。 */
    private fun reasonLabel(reason: DowngradeReason): String = when (reason) {
        DowngradeReason.FAKE_FULL_BAR -> "假满格"
        DowngradeReason.WEAK_SIGNAL -> "信号过差"
        DowngradeReason.NONE -> "未知成因"
    }

    private fun modeLabel(mode: Int): String =
        NetworkMode.fromValue(mode)?.label ?: "模式 $mode"

    /**
     * 启动自愈：只要上次退出时还停在「已降级」，就无条件把完整制式写回去。
     *
     * 这是对原脚本「PNM 卡在 9」缺陷的正面修复 —— 宁愿多恢复一次，也不能让用户
     * 重启后以为手机丢了 5G。
     */
    suspend fun selfHeal() {
        val current = _state.value
        if (!current.active) return
        LogStore.log(
            TAG,
            "检测到上次退出时仍处于降级态（留档 ${current.lastEvent}），执行启动自愈",
            LogLevel.WARN,
        )
        val subId = SignalReader.defaultDataSubId()
        val thresholds = MonitorSettings.thresholds()
        val target = restoreTarget(subId, current, thresholds)
        val ok = writeMode(subId, target, thresholds)
        LogStore.log(
            TAG,
            if (ok) "启动自愈完成，制式已写回 $target（${modeLabel(target)}）" else "启动自愈写回失败",
            if (ok) LogLevel.INFO else LogLevel.ERROR,
        )
        finish(current, ok, "启动自愈")
    }

    /** 手动清除降级状态（不写回制式，仅清计数）。 */
    fun resetState() {
        val cleared = DowngradeState(lastEvent = "状态已手动清除", lastEventAtMs = System.currentTimeMillis())
        persist(cleared)
        _state.value = cleared
    }

    private fun loadState(): DowngradeState {
        val raw = prefs.getString(KEY_STATE, null) ?: return DowngradeState()
        return runCatching {
            val json = JSONObject(raw)
            DowngradeState(
                active = json.optBoolean("active", false),
                degradedAtMs = json.optLong("degradedAtMs", 0L),
                recoveryCount = json.optInt("recoveryCount", 0),
                noNetFailCount = json.optInt("noNetFailCount", 0),
                appliedMode = json.optInt("appliedMode", -1),
                savedMode = json.optInt("savedMode", -1),
                lastEvent = json.optString("lastEvent", ""),
                lastEventAtMs = json.optLong("lastEventAtMs", 0L),
            )
        }.getOrDefault(DowngradeState())
    }

    /**
     * 落盘（1.5.3 起带脏检查）。
     *
     * 为什么必须查脏：降级态里 [applyTransition] 每轮都会调一次 persist —— 冷却中
     * （默认 120 秒）和「仍满足降级条件」这两条路径都会走到，而这两条路径**根本不改
     * 状态**。改动前每轮都要序列化一遍再排一次 SharedPreferences 写盘，一个持续存在的
     * 假满格网络就等于每个采样周期一次无意义写入。内容与上次一致时直接返回；状态真正
     * 变了（降级成功 / 恢复计数 / 无网计数 / 自愈 / 手动清除）时序列化结果必然不同，
     * 落盘时机一个字没变。
     */
    private fun persist(state: DowngradeState) {
        val json = JSONObject()
            .put("active", state.active)
            .put("degradedAtMs", state.degradedAtMs)
            .put("recoveryCount", state.recoveryCount)
            .put("noNetFailCount", state.noNetFailCount)
            .put("appliedMode", state.appliedMode)
            .put("savedMode", state.savedMode)
            .put("lastEvent", state.lastEvent)
            .put("lastEventAtMs", state.lastEventAtMs)
            .toString()
        if (json == lastPersistedJson) return
        lastPersistedJson = json
        prefs.edit().putString(KEY_STATE, json).apply()
    }

    companion object {
        const val TAG = "降级引擎"
        private const val PREFS_NAME = "netpilot_downgrade"
        private const val KEY_STATE = "state"

        /**
         * 息屏后台轮次的探测降频窗口（1.5.3）。
         *
         * 屏幕关着的时候，一次 HTTP 探测的唯一读者是「假满格」那条判定（见 [tick]），
         * 而那条规则本来就是「连续多轮才动作」；把息屏探测压到 5 分钟一次，换来息屏期间
         * 约 5 倍的探测/网络唤醒次数减少。亮屏后立刻恢复每轮真探。
         */
        private const val BACKGROUND_PROBE_BACKOFF_MS = 5 * 60 * 1000L
    }
}
