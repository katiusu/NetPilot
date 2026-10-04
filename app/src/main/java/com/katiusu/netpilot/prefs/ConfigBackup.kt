package com.katiusu.netpilot.prefs

import android.content.Context
import com.katiusu.netpilot.AppSettings
import com.katiusu.netpilot.core.datacard.DataCardRule
import com.katiusu.netpilot.core.datacard.DataCardStore
import com.katiusu.netpilot.core.datacard.SimPolicy
import com.katiusu.netpilot.core.datacard.SimPolicyMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一配置导出 / 导入（JSON）。
 *
 * 之前的实现只遍历 [PrefsStore]（也就是 Miuix 偏好框架里那份
 * `miuix_template_prefs`），漏了外观设置（`app_settings`）和双卡策略 /
 * Wi-Fi 规则（`netpilot_datacard`）——结果是「导出成功、导入成功，但界面
 * 一点变化都没有」。现在按分区导出，导入时同时对旧版扁平格式做兼容。
 *
 * v2 结构：
 * ```json
 * {
 *   "_format": 2,
 *   "prefs":     { "<PrefsStore 键>": 值, ... },
 *   "app_settings": { "themeMode": "...", ... },
 *   "datacard":  { "home_sub_id": -1, "rules_enabled": true,
 *                  "policies": [...], "rules": [...] }
 * }
 * ```
 */
object ConfigBackup {

    private const val SKIP_PREFIX = "runtime_"
    private const val FORMAT_VERSION = 2

    private const val KEY_FORMAT = "_format"
    private const val SECTION_PREFS = "prefs"
    private const val SECTION_APP = "app_settings"
    private const val SECTION_DATACARD = "datacard"

    /** 导出全部可迁移配置。 */
    fun exportJson(context: Context): String {
        val root = JSONObject()
        root.put(KEY_FORMAT, FORMAT_VERSION)

        // 1) Miuix 偏好框架：选中项注册表 + 网络质量策略参数都在这份里。
        val prefs = JSONObject()
        PrefsStore.getAll().forEach { (key, value) ->
            if (key.startsWith(SKIP_PREFIX)) return@forEach
            prefs.put(key, toJsonValue(value))
        }
        root.put(SECTION_PREFS, prefs)

        // 2) 外观设置。以前漏掉，是「导入导出没反应」的原因之一。
        root.put(SECTION_APP, JSONObject(AppSettings.load(context).toJson()))

        // 3) 双卡策略与 Wi-Fi 规则。同样以前漏掉。
        val dataCard = JSONObject()
        dataCard.put("home_sub_id", DataCardStore.homeSubId(context))
        dataCard.put("rules_enabled", DataCardStore.rulesEnabled(context))

        val policies = JSONArray()
        DataCardStore.policies(context).values.forEach { p ->
            policies.put(
                JSONObject().apply {
                    put("subId", p.subId)
                    put("slotIndex", p.slotIndex)
                    put("mode", p.mode.name)
                    put("enabled", p.enabled)
                    put("dataAllowed", p.dataAllowed)
                    put("downgradeModeValue", p.downgradeModeValue)
                    // 「自定义」模式下生效的两条子策略。漏掉的话，备份→恢复后
                    // 用户勾的自定义策略会悄悄退回默认值。
                    put("customNetworkQuality", p.customNetworkQuality)
                    put("customWifiDowngrade", p.customWifiDowngrade)
                }
            )
        }
        dataCard.put("policies", policies)

        val rules = JSONArray()
        DataCardStore.rules(context).forEach { r ->
            rules.put(
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
                }
            )
        }
        dataCard.put("rules", rules)
        root.put(SECTION_DATACARD, dataCard)

        return root.toString(2)
    }

    /** 导入配置；返回 false 表示 JSON 不合法或写入过程中抛异常。 */
    fun importJson(context: Context, json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val isSectioned = root.has(SECTION_PREFS) ||
                root.has(SECTION_DATACARD) ||
                root.optInt(KEY_FORMAT, 0) >= 2

            if (!isSectioned) {
                // v1 兼容：整个 JSON 对象就是 PrefsStore 的一份扁平快照。
                applyPrefs(root)
            } else {
                root.optJSONObject(SECTION_PREFS)?.let { applyPrefs(it) }
                root.optJSONObject(SECTION_APP)?.let { section ->
                    AppSettings.save(context, AppSettings.fromJson(section.toString()))
                }
                root.optJSONObject(SECTION_DATACARD)?.let { applyDataCard(context, it) }
            }

            ConfigState.reload()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyPrefs(prefs: JSONObject) {
        prefs.keys().forEach { key ->
            val value: Any? = when (val raw = prefs.get(key)) {
                is JSONArray -> (0 until raw.length()).map { raw.getString(it) }.toSet()
                JSONObject.NULL -> null
                else -> raw
            }
            PrefsStore.put(key, value)
        }
    }

    private fun applyDataCard(context: Context, section: JSONObject) {
        if (section.has("home_sub_id")) {
            DataCardStore.setHomeSubId(context, section.optInt("home_sub_id", -1))
        }
        if (section.has("rules_enabled")) {
            DataCardStore.setRulesEnabled(context, section.optBoolean("rules_enabled", true))
        }

        section.optJSONArray("policies")?.let { arr ->
            val list = ArrayList<SimPolicy>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list += SimPolicy(
                    subId = o.optInt("subId", -1),
                    slotIndex = o.optInt("slotIndex", 0),
                    mode = parsePolicyMode(o.optString("mode", "")),
                    enabled = o.optBoolean("enabled", true),
                    dataAllowed = o.optBoolean("dataAllowed", true),
                    downgradeModeValue = o.optInt("downgradeModeValue", 9),
                    customNetworkQuality = o.optBoolean("customNetworkQuality", true),
                    customWifiDowngrade = o.optBoolean("customWifiDowngrade", false),
                )
            }
            if (list.isNotEmpty()) DataCardStore.savePolicies(context, list)
        }

        section.optJSONArray("rules")?.let { arr ->
            val list = ArrayList<DataCardRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list += DataCardRule(
                    id = o.optString("id", "rule_$i"),
                    name = o.optString("name", ""),
                    enabled = o.optBoolean("enabled", true),
                    ssid = o.optString("ssid", ""),
                    bssid = o.optString("bssid", ""),
                    targetSubId = o.optInt("targetSubId", -1),
                    targetModeValue = o.optInt("targetModeValue", 0),
                    priority = o.optInt("priority", 0),
                    cooldownSec = o.optInt("cooldownSec", 120),
                    revertOnLeave = o.optBoolean("revertOnLeave", true),
                )
            }
            DataCardStore.saveRules(context, list)
        }
    }

    /**
     * 解析卡策略模式，并兼容旧备份里的历史枚举名。
     *
     * `AUTO_DOWNGRADE` 是本项目早期的「假 5G 自动降级」，现已改名为
     * [SimPolicyMode.NETWORK_QUALITY]；`FIXED_MODE`（固定制式）这个功能已经整体
     * 删除，老备份里锁了制式的卡退回「跟随系统」，而不是让整份配置导入失败。
     */
    private fun parsePolicyMode(raw: String): SimPolicyMode = when (raw) {
        "AUTO_DOWNGRADE" -> SimPolicyMode.NETWORK_QUALITY
        "FIXED_MODE" -> SimPolicyMode.FOLLOW_SYSTEM
        else -> runCatching { SimPolicyMode.valueOf(raw) }
            .getOrDefault(SimPolicyMode.NETWORK_QUALITY)
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Set<*> -> JSONArray(value)
        else -> value
    }
}
