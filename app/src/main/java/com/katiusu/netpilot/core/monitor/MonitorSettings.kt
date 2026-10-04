package com.katiusu.netpilot.core.monitor

import android.content.Context
import android.telephony.TelephonyManager
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.prefs.ConfigState

/**
 * 网络质量相关配置的键与默认值。
 *
 * 键名里的 `np_fake5g_` 前缀沿用旧命名、**必须保留**：老用户已经写在 SharedPreferences
 * 里的值靠它才读得回来，重命名等于把所有人的配置清空。改名只发生在用户可见文案上。
 *
 * 默认值**逐条对应** Network_Enhance `config.sh`，键名统一加 `np_` 前缀，
 * 与模板里 `example_*` 的示例键不冲突。UI 侧用 [OptionSpec] 引用这里的常量，
 * 保证「默认值只有一处定义」。
 */
object MonitorSettings {

    const val KEY_ENABLED = "np_fake5g_enabled"
    const val KEY_RSRP = "np_fake5g_rsrp"
    const val KEY_SINR = "np_fake5g_sinr"
    const val KEY_PING = "np_fake5g_ping"
    const val KEY_COOLDOWN = "np_fake5g_cooldown"
    const val KEY_RECOVERY = "np_fake5g_recovery"
    const val KEY_NO_NET_ROLLBACK = "np_fake5g_no_net_rollback"
    const val KEY_INTERVAL = "np_fake5g_interval"
    const val KEY_DOWNGRADE_MODE = "np_fake5g_downgrade_mode"
    const val KEY_LOCK_LTE_MODE = "np_fake5g_lock_lte_mode"
    const val KEY_PING_FAIL = "np_fake5g_ping_fail"
    const val KEY_TOGGLE_ENDC = "np_fake5g_endc"

    /**
     * 「信号过差」规则的 RSRP 门限，单位 dBm。
     *
     * 与 [KEY_RSRP] 是**两条独立的规则**：那个管「信号满格但没质量」，这个管
     * 「信号本身就差」。两者共用同一个键会互相干扰，所以各留一个。
     */
    const val KEY_WEAK_RSRP = "np_fake5g_weak_rsrp"

    /** 是否启用「信号过差」降级；关掉后弱信号只记录、不修改制式。 */
    const val KEY_WEAK_SIGNAL = "np_fake5g_weak_signal"

    const val KEY_AUTO_START = "np_monitor_autostart"

    /**
     * 网络质量降级的默认开关状态：**默认开启**。
     *
     * 这是本应用的核心功能，装完即生效比「先去功能页找一个开关打开」更符合预期。
     * 关掉它只影响「是否写网络制式」，采样、判定与日志照旧跑。
     * 这是默认值的**唯一真源**，[com.katiusu.netpilot.core.NetPilot.autoDowngradeEnabled]
     * 也读它，避免两处各写一个字面量后改一处漏一处。
     */
    const val DEFAULT_ENABLED = true

    const val DEFAULT_RSRP = -85
    const val DEFAULT_SINR = 0
    const val DEFAULT_PING = 200
    /** 降级冷却默认 300 秒（原脚本 1800 太长，见 DowngradeThresholds.cooldownSec）。 */
    const val DEFAULT_COOLDOWN = 300
    const val DEFAULT_RECOVERY = 3
    const val DEFAULT_NO_NET_ROLLBACK = 2
    const val DEFAULT_INTERVAL = 120
    const val DEFAULT_DOWNGRADE_MODE = 9
    /** 0 = 跟随运营商（5G 自动），见 DowngradeThresholds.lockLteMode。 */
    const val DEFAULT_LOCK_LTE_MODE = 0
    /** 「信号过差」门限默认 -110 dBm，与 Network_Enhance 的弱信号判据一致。 */
    const val DEFAULT_WEAK_RSRP = -110
    /** 「信号过差」规则默认开启：弱 5G 往往比稳定的 4G 更慢、更耗电。 */
    const val DEFAULT_WEAK_SIGNAL = true
    /**
     * 读取整型配置。
     *
     * 下拉卡片把选中的是 `entryValues` 里的**字符串**（如 "9"），滑块写的是 Float，
     * 所以这里不能直接用 `ConfigState.int`——它只接受 Number，遇到字符串会静默
     * 退回默认值，表现就是「下拉选了但策略没变」。统一在这里做一次收敛。
     */
    private fun intValue(key: String, default: Int): Int = when (val raw = ConfigState.get(key)) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull() ?: default
        else -> default
    }

    fun thresholds(): DowngradeThresholds = DowngradeThresholds(
        enabled = ConfigState.bool(KEY_ENABLED, DEFAULT_ENABLED),
        rsrpThreshold = intValue(KEY_RSRP, DEFAULT_RSRP),
        sinrThreshold = intValue(KEY_SINR, DEFAULT_SINR),
        pingThresholdMs = intValue(KEY_PING, DEFAULT_PING),
        cooldownSec = intValue(KEY_COOLDOWN, DEFAULT_COOLDOWN),
        recoveryCount = intValue(KEY_RECOVERY, DEFAULT_RECOVERY),
        noNetRollbackCount = intValue(KEY_NO_NET_ROLLBACK, DEFAULT_NO_NET_ROLLBACK),
        monitorIntervalSec = intValue(KEY_INTERVAL, DEFAULT_INTERVAL),
        downgradeMode = intValue(KEY_DOWNGRADE_MODE, DEFAULT_DOWNGRADE_MODE),
        lockLteMode = intValue(KEY_LOCK_LTE_MODE, DEFAULT_LOCK_LTE_MODE),
        downgradeOnPingFail = ConfigState.bool(KEY_PING_FAIL, false),
        toggleEndc = ConfigState.bool(KEY_TOGGLE_ENDC, false),
        weakRsrpThreshold = effectiveWeakRsrp(),
        downgradeOnWeakSignal = ConfigState.bool(KEY_WEAK_SIGNAL, DEFAULT_WEAK_SIGNAL),
    )

    /**
     * 读弱信号阈值，并保证它严格低于强信号阈值。
     *
     * 为什么必须收敛：两条规则的前提分别是 `RSRP > 高阈值`（有资格判假满格）与
     * `RSRP < 低阈值`（判定信号过差直接降级）。一旦低阈值被设成不低于高阈值，
     * 同一个 RSRP 会同时落进两条规则的前提里，而判定顺序上「强信号」优先，结果就是
     * 用户明明把低阈值拉到 -80，实际却按高阈值的边界在动作 —— 这种「填的数字没生效」
     * 是最难自查的一类问题，所以在读取侧直接收敛掉。
     *
     * 只收敛生效值，不改用户存下来的数字：他把滑条调回合理区间，配置立刻复原。
     */
    private fun effectiveWeakRsrp(): Int {
        val strong = intValue(KEY_RSRP, DEFAULT_RSRP)
        val weak = intValue(KEY_WEAK_RSRP, DEFAULT_WEAK_RSRP)
        return if (weak < strong) weak else strong - 1
    }
}

/** 读取运营商信息，用于把「恢复」写回该运营商的默认模式。 */
object CarrierInfo {

    /**
     * 取指定卡的运营商默认模式值；读不到时退回模式 26（NR/LTE/GSM/WCDMA）。
     *
     * 用 `TelephonyManager.getSimOperator()` 而不是 `SubscriptionManager`：
     * 前者是公开 API、不需要 READ_PHONE_STATE，返回形如 `"46003"` 的 MCC+MNC 串；
     * 而 `SubscriptionManager.getActiveSubscriptionInfoList()` 的静态形式不在公开 SDK 里
     * （只有实例方法，需要 Context + 运行时电话权限），`getActiveSubscriptionInfo(int)`
     * 同样是 @hide。SIM 未就绪时 `simOperator` 会是空串，交给 [NetworkMode.carrierDefault] 兜底。
     */
    fun defaultModeForActiveSubscription(context: Context, subId: Int): Int {
        val mccMnc = runCatching {
            val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val scoped = if (subId >= 0) manager?.createForSubscriptionId(subId) else manager
            scoped?.simOperator.orEmpty()
        }.getOrDefault("")
        val mcc = if (mccMnc.length >= 5) mccMnc.substring(0, 3) else null
        val mnc = if (mccMnc.length >= 5) mccMnc.substring(3) else null
        val mode = runCatching { NetworkMode.carrierDefault(mcc, mnc) }.getOrNull()
        return (mode ?: NetworkMode.fromValue(26))?.value ?: 26
    }

    /** 运营商展示名，读不到返回空串。 */
    fun operatorName(context: Context, subId: Int): String = runCatching {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val scoped = if (subId >= 0) manager?.createForSubscriptionId(subId) else manager
        scoped?.networkOperatorName.orEmpty()
    }.getOrDefault("")
}
