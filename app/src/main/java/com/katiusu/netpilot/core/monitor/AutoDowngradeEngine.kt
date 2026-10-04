package com.katiusu.netpilot.core.monitor

import com.katiusu.netpilot.core.NetPilotEvents
import android.content.Context
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

    /** 跑一轮：采样 → 判定 → 执行状态机。返回本轮快照，供 UI 与服务复用。 */
    /** 必须在非主线程调用：内部含 HTTP 探测与特权 shell 等阻塞操作。 */
    suspend fun tick(): SignalSnapshot {
        val thresholds = MonitorSettings.thresholds()
        val subId = SignalReader.defaultDataSubId()
        val snapshot = SignalReader.read(
            context = context,
            subId = subId,
            pingTarget = thresholds.pingTarget,
            pingTimeoutMs = thresholds.pingTimeoutMs,
        )
        val judgement = FakeSignalDetector.judge(snapshot, thresholds)
        _snapshot.value = snapshot
        _judgement.value = judgement
        applyTransition(snapshot, judgement, thresholds)
        return snapshot
    }

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
        val noResponse = snapshot.pingMs == null && !snapshot.isWifi

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
        val channel = ControlManager.acquire() ?: return -1
        val mode = channel.getMode(subId)
        return mode.takeIf { it >= 0 } ?: -1
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
        prefs.edit().putString(KEY_STATE, json.toString()).apply()
    }

    companion object {
        const val TAG = "降级引擎"
        private const val PREFS_NAME = "netpilot_downgrade"
        private const val KEY_STATE = "state"
    }
}
