package com.katiusu.netpilot.core.datacard

import android.content.Context
import com.katiusu.netpilot.core.monitor.LogStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 双卡策略与 Wi-Fi 规则的持久化。
 *
 * 用 org.json 手工序列化而不是引 Gson/Moshi：整个项目只此一处需要 JSON，
 * 为一个 Prefs 存两三个数组再拉一个 200KB 的依赖不划算。
 *
 * 所有读操作都做了「字段缺失回退到默认值」处理 —— 用户的配置文件是被
 * 用户自己改过、或被旧版本写过、或压根不存在，三种情况都不能崩。
 *
 * 历史：曾经有过一版「卡订阅规则」的模型（[SimPolicy] 带 `ruleIds`），规则切到哪张卡
 * 由卡勾选规则决定。那个模型已经按用户要求撤掉，规则求值改回「规则自带 targetSubId」，
 * 相关的 `schema_version` 一次性迁移也一并删除。旧枚举名（`AUTO_DOWNGRADE` /
 * `FIXED_MODE`）的兼容映射保留在 [parseMode] 里，见那里的说明。
 */
object DataCardStore {

    private const val TAG = "双卡配置"
    private const val PREFS = "netpilot_datacard"
    private const val KEY_POLICIES = "policies"
    private const val KEY_RULES = "rules"
    private const val KEY_HOME_SUB = "home_sub_id"
    private const val KEY_RULES_ENABLED = "rules_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 卡策略 ----------------

    /** 读取卡策略（subId → 策略）。空 Map 表示用户还没配置过任何卡。 */
    fun policies(context: Context): Map<Int, SimPolicy> = readPolicies(context)

    private fun readPolicies(context: Context): Map<Int, SimPolicy> {
        return runCatching {
            val raw = prefs(context).getString(KEY_POLICIES, null)
            if (raw.isNullOrBlank()) {
                emptyMap()
            } else {
                val arr = JSONArray(raw)
                buildMap {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val subId = o.optInt("subId", -1)
                        if (subId < 0) continue
                        put(
                            subId,
                            SimPolicy(
                                subId = subId,
                                slotIndex = o.optInt("slotIndex", 0),
                                mode = parseMode(
                                    o.optString("mode", SimPolicyMode.NETWORK_QUALITY.name),
                                ),
                                enabled = o.optBoolean("enabled", true),
                                dataAllowed = o.optBoolean("dataAllowed", true),
                                downgradeModeValue = o.optInt("downgradeModeValue", 9),
                                customNetworkQuality = o.optBoolean("customNetworkQuality", true),
                                customWifiDowngrade = o.optBoolean("customWifiDowngrade", false),
                            ),
                        )
                    }
                }
            }
        }.getOrElse { t ->
            LogStore.warn(TAG, "解析卡策略失败，按默认处理：${t.message ?: t.javaClass.simpleName}")
            emptyMap()
        }
    }

    /**
     * 反序列化模式名，兼容旧配置里已经被删掉的枚举常量。
     *
     * 不做映射的后果不是「选项变了」而是「整份配置消失」：`SimPolicyMode.valueOf("FIXED_MODE")`
     * 会抛 IllegalArgumentException，被外层 runCatching 吞掉后用户所有卡的策略一起回默认。
     *
     * - `AUTO_DOWNGRADE`：旧名「假 5G 自动降级」→ [SimPolicyMode.NETWORK_QUALITY]（只是改名）
     * - `FIXED_MODE`：旧「固定制式」，功能已删除 → [SimPolicyMode.FOLLOW_SYSTEM]（安全退化：
     *   不再替用户锁死制式，而不是悄悄换成另一种会改制式的策略）
     */
    private fun parseMode(raw: String): SimPolicyMode = when (raw) {
        "AUTO_DOWNGRADE" -> SimPolicyMode.NETWORK_QUALITY
        "FIXED_MODE" -> SimPolicyMode.FOLLOW_SYSTEM
        else -> runCatching { SimPolicyMode.valueOf(raw) }
            .getOrDefault(SimPolicyMode.NETWORK_QUALITY)
    }

    fun savePolicies(context: Context, policies: Collection<SimPolicy>) {
        runCatching {
            val arr = JSONArray()
            policies.forEach { p ->
                arr.put(
                    JSONObject().apply {
                        put("subId", p.subId)
                        put("slotIndex", p.slotIndex)
                        put("mode", p.mode.name)
                        put("enabled", p.enabled)
                        put("dataAllowed", p.dataAllowed)
                        put("downgradeModeValue", p.downgradeModeValue)
                        put("customNetworkQuality", p.customNetworkQuality)
                        put("customWifiDowngrade", p.customWifiDowngrade)
                    },
                )
            }
            prefs(context).edit().putString(KEY_POLICIES, arr.toString()).apply()
        }.onFailure { LogStore.error(TAG, "保存卡策略失败：${it.message}") }
    }

    /**
     * 取某张卡的策略；没有配置过就现造一个默认策略（开启网络质量降级）。
     * 这样用户双卡插上来的第一秒就已经被策略覆盖，不用先去设置页点一遍。
     */
    fun policyOf(context: Context, subId: Int, slotIndex: Int): SimPolicy =
        policies(context)[subId] ?: SimPolicy(subId = subId, slotIndex = slotIndex)

    fun setPolicy(context: Context, policy: SimPolicy) {
        val map = policies(context).toMutableMap()
        map[policy.subId] = policy
        savePolicies(context, map.values)
    }

    // ---------------- Wi-Fi 规则 ----------------

    fun rulesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RULES_ENABLED, true)

    fun setRulesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RULES_ENABLED, enabled).apply()
    }

    /** 按 `priority` 降序读出规则；同优先级保持写入顺序（sortedByDescending 是稳定排序）。 */
    fun rules(context: Context): List<DataCardRule> = runCatching {
        val raw = prefs(context).getString(KEY_RULES, null) ?: return emptyList()
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    DataCardRule(
                        id = o.optString("id", "rule_$i"),
                        name = o.optString("name", "规则 $i"),
                        enabled = o.optBoolean("enabled", true),
                        ssid = o.optString("ssid", ""),
                        bssid = o.optString("bssid", ""),
                        targetSubId = o.optInt("targetSubId", -1),
                        targetModeValue = o.optInt("targetModeValue", 0),
                        priority = o.optInt("priority", 0),
                        cooldownSec = o.optInt("cooldownSec", 120),
                        revertOnLeave = o.optBoolean("revertOnLeave", true),
                    ),
                )
            }
        }.sortedByDescending { it.priority }
    }.getOrElse { t ->
        LogStore.warn(TAG, "解析 Wi-Fi 规则失败，按无规则处理：${t.message ?: t.javaClass.simpleName}")
        emptyList()
    }

    fun saveRules(context: Context, rules: List<DataCardRule>) {
        runCatching {
            val arr = JSONArray()
            rules.forEach { r ->
                arr.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("name", r.name)
                        put("enabled", r.enabled)
                        put("ssid", r.ssid)
                        put("bssid", r.bssid)
                        put("targetSubId", r.targetSubId)
                        put("targetModeValue", r.targetModeValue)
                        put("priority", r.priority)
                        put("cooldownSec", r.cooldownSec)
                        put("revertOnLeave", r.revertOnLeave)
                    },
                )
            }
            prefs(context).edit().putString(KEY_RULES, arr.toString()).apply()
        }.onFailure { LogStore.error(TAG, "保存 Wi-Fi 规则失败：${it.message}") }
    }

    /** 离开 Wi-Fi 后回切的目标卡。 */
    fun homeSubId(context: Context): Int =
        prefs(context).getInt(KEY_HOME_SUB, SimReader.defaultDataSubId())

    fun setHomeSubId(context: Context, subId: Int) {
        prefs(context).edit().putInt(KEY_HOME_SUB, subId).apply()
    }
}
