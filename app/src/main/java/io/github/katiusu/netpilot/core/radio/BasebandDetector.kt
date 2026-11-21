package io.github.katiusu.netpilot.core.radio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.katiusu.netpilot.core.monitor.LogStore
import io.github.katiusu.netpilot.prefs.ConfigState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 基带厂商只读探测。
 *
 * 三点判据，从便宜到贵：系统属性 → 特征文件/AT 节点（只 stat）→ phoneEx 服务。
 * 全程只读：不写属性、不对服务做任何远端调用、不打开 /dev 节点。
 *
 * 为什么不碰 AT：/dev/radio/atci1 这类节点是 RIL 独占的 modem 串口，open 会把它从 RIL
 * 手里抢走，表现是短暂无服务甚至掉网。这一版只 stat 路径存在性，日志里每次都显式写
 * 「本次未做 AT 探测」——免得以后有人看到节点名以为这里发过 AT 指令。
 */
object BasebandDetector {

    private const val TAG = "基带"

    /** 结论缓存键。放 ConfigState 是为了跟着配置导入导出走，界面也能直接读到。 */
    private const val KEY_PROFILE = "np_baseband_profile"

    /** 单次探测的总预算。三条判据都是毫秒级 stat/反射，超时只可能是环境异常。 */
    private const val TOTAL_TIMEOUT_MS = 3_000L

    /** 结论有效期。硬件不会变，但刷机/换 ROM 会变，留一个会自动过期的窗口。 */
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 证据行上限，防止某些机型吐出上百条同类路径把日志页刷满。 */
    private const val MAX_EVIDENCE_LINES = 24

    private val PROP_KEYS = listOf("ro.board.platform", "ro.hardware", "ro.boot.hardware")

    /** 只 stat，绝不 open。 */
    private val AT_NODE_PATHS = listOf("/dev/radio/atci1", "/dev/radio/pttycmd1", "/dev/ttyC0")

    private val MTK_FEATURE_FILES = listOf("/vendor/lib64/libmtk-ril.so", "/vendor/bin/atcid")

    private val QUALCOMM_LIB_PREFIXES = listOf("libqmi", "libqcrilNr")

    private val QUALCOMM_PREFIXES = listOf(
        "qcom", "kona", "lahaina", "taro", "kalama", "pineapple", "sm8", "sm7", "msm", "sdm",
    )
    private val MEDIATEK_PREFIXES = listOf("mt6", "mt68", "mt69", "mtk")
    private val EXYNOS_PREFIXES = listOf("exynos", "universal")
    private val UNISOC_PREFIXES = listOf("sprd", "ums", "t606", "sc98")

    /**
     * 跑一次只读探测并落盘结论。
     *
     * 挂起函数而不是普通函数：它要做 stat 与服务查询，调用方（启动流程）只该把结果等成
     * 后台任务，不该在任何 UI 线程上同步等它。
     */
    suspend fun detect(context: Context): BasebandProfile = withContext(Dispatchers.IO) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            val noTelephony = unknownProfile("设备没有 telephony 特性，不做基带判定")
            LogStore.info(TAG, noTelephony.logText())
            return@withContext noTelephony
        }
        val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
        // 超时兜底：真超时说明环境异常（例如 SELinux 反复拒绝），宁可给「无结论」
        // 也不要让调用方一直等下去。
        val profile = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { probe(deadline) }
            ?: unknownProfile("探测超过 " + TOTAL_TIMEOUT_MS + " ms 未完成，本次放弃")
        // 失败或 UNKNOWN 不落盘：一次异常不该被缓存成七天的结论，下次启动重试。
        if (profile.vendor != BasebandVendor.UNKNOWN && profile.confidence != Confidence.UNKNOWN) {
            save(profile)
        }
        profile
    }

    // ---------------- 探测本体 ----------------

    private fun probe(deadline: Long): BasebandProfile {
        val evidence = ArrayList<String>()
        val atNodes = ArrayList<String>()
        val votes = LinkedHashMap<BasebandVendor, Int>()

        // ---- 判据一：系统属性（最便宜，一次反射）----
        val propLines = ArrayList<String>()
        val noteProperty = { source: String, raw: String ->
            val value = raw.trim()
            if (value.isEmpty()) {
                propLines += "属性 " + source + " 为空"
            } else {
                val vendor = vendorOfPlatform(value)
                if (vendor == BasebandVendor.UNKNOWN) {
                    propLines += "属性 " + source + "=" + value + "（前缀无法归类）"
                } else {
                    propLines += "属性 " + source + "=" + value + " → " + BasebandProfile.labelOf(vendor)
                    votes[vendor] = (votes[vendor] ?: 0) + 1
                }
            }
        }
        PROP_KEYS.forEach { key -> noteProperty(key, readProp(key)) }
        noteProperty("Build.HARDWARE", Build.HARDWARE)
        noteProperty("Build.BOARD", Build.BOARD)
        evidence += propLines

        if (overBudget(deadline)) {
            evidence += "探测预算已用完，跳过特征文件与服务查询"
            return buildProfile(BasebandVendor.UNKNOWN, Confidence.UNKNOWN, evidence, atNodes)
        }

        // ---- 判据二：特征文件与 AT 节点（只 stat，绝不 open）----
        AT_NODE_PATHS.forEach { path ->
            if (existsOnly(path)) {
                atNodes += path
                evidence += "AT 节点 " + path + " 存在（未打开）"
            }
        }
        // atci1 / pttycmd1 是联发科 atcid 的输入节点，出现算一条联发科佐证；
        // /dev/ttyC0 各家都有，只记录存在性、不参与投票，免得把它当成厂商特征。
        val mtkAtNodeHit = atNodes.any { path -> path.endsWith("/atci1") || path.endsWith("/pttycmd1") }

        val mtkFileHits = MTK_FEATURE_FILES.filter { path -> existsOnly(path) }
        mtkFileHits.forEach { path -> evidence += "命中特征文件 " + path + " → 联发科" }

        val qcomLibHits = listQualcommLibs()
        qcomLibHits.forEach { path -> evidence += "命中特征库 " + path + " → 高通" }

        if (overBudget(deadline)) {
            evidence += "探测预算已用完，跳过服务查询"
            return buildProfile(BasebandVendor.UNKNOWN, Confidence.UNKNOWN, evidence, atNodes)
        }

        // ---- 判据三：phoneEx 服务（只 getService，不做任何远端调用）----
        val hasPhoneEx = runCatching { hasService(BasebandProfile.PHONE_EX_SERVICE) }
            .getOrDefault(false)
        if (hasPhoneEx) {
            evidence += "ServiceManager.getService('" + BasebandProfile.PHONE_EX_SERVICE +
                "') 返回非空 → 联发科"
        }

        if (votes.size > 1) {
            val detail = votes.entries.joinToString("、") { entry ->
                BasebandProfile.labelOf(entry.key) + "×" + entry.value
            }
            evidence += "属性之间互相冲突：" + detail
        }

        val propVendor = votes.maxByOrNull { entry -> entry.value }?.key ?: BasebandVendor.UNKNOWN
        val propVotes = votes[propVendor] ?: 0
        val qcomCorroborated = qcomLibHits.isNotEmpty()
        val mtkCorroborated = hasPhoneEx || mtkFileHits.isNotEmpty() || mtkAtNodeHit

        val verdict = when {
            // 冲突规则优先级最高：属性说高通，但联发科的服务或特征文件在，以它们为准。
            propVendor == BasebandVendor.QUALCOMM && (hasPhoneEx || mtkFileHits.isNotEmpty()) -> {
                evidence += "冲突：属性指向高通，但 phoneEx 或联发科特征文件存在；以服务/文件为准判为联发科"
                BasebandVendor.MEDIATEK to Confidence.LIKELY
            }
            propVendor == BasebandVendor.MEDIATEK -> {
                if (mtkCorroborated) {
                    BasebandVendor.MEDIATEK to Confidence.CONFIRMED
                } else {
                    evidence += "属性指向联发科，但 phoneEx 与特征文件都没命中 → 只标较可能"
                    BasebandVendor.MEDIATEK to Confidence.LIKELY
                }
            }
            propVendor == BasebandVendor.QUALCOMM ->
                if (qcomCorroborated) {
                    BasebandVendor.QUALCOMM to Confidence.CONFIRMED
                } else {
                    BasebandVendor.QUALCOMM to Confidence.LIKELY
                }
            // 这两家没有可用的只读特征文件，只能靠多个属性互相印证升到已确认。
            propVendor == BasebandVendor.EXYNOS || propVendor == BasebandVendor.UNISOC ->
                if (propVotes >= 2) propVendor to Confidence.CONFIRMED
                else propVendor to Confidence.LIKELY
            // 属性读不出结论时，才轮到服务与文件单独定案。
            hasPhoneEx || mtkFileHits.isNotEmpty() || mtkAtNodeHit -> {
                evidence += "属性无法归类，靠 phoneEx / 特征文件判定为联发科"
                BasebandVendor.MEDIATEK to Confidence.LIKELY
            }
            qcomLibHits.isNotEmpty() -> {
                evidence += "属性无法归类，靠 QMI/QCRIL 特征库判定为高通"
                BasebandVendor.QUALCOMM to Confidence.LIKELY
            }
            else -> {
                evidence += "三条判据都没有命中，本次不给结论"
                BasebandVendor.UNKNOWN to Confidence.UNKNOWN
            }
        }

        return buildProfile(verdict.first, verdict.second, evidence, atNodes)
    }

    private fun overBudget(deadline: Long): Boolean = System.currentTimeMillis() > deadline

    private fun buildProfile(
        vendor: BasebandVendor,
        confidence: Confidence,
        evidence: List<String>,
        atNodes: List<String>,
    ): BasebandProfile = BasebandProfile(
        vendor = vendor,
        confidence = confidence,
        evidence = evidence.take(MAX_EVIDENCE_LINES),
        atNodes = atNodes.toList(),
        // 只读版本：即便识别出联发科，也不能声称自己会改制式。能力集留给后续版本填。
        capabilities = emptySet(),
    )

    private fun unknownProfile(reason: String): BasebandProfile = buildProfile(
        vendor = BasebandVendor.UNKNOWN,
        confidence = Confidence.UNKNOWN,
        evidence = listOf(reason),
        atNodes = emptyList(),
    )

    private fun vendorOfPlatform(value: String): BasebandVendor {
        val v = value.trim().lowercase()
        if (v.isEmpty()) return BasebandVendor.UNKNOWN
        return when {
            QUALCOMM_PREFIXES.any { v.startsWith(it) } -> BasebandVendor.QUALCOMM
            MEDIATEK_PREFIXES.any { v.startsWith(it) } -> BasebandVendor.MEDIATEK
            EXYNOS_PREFIXES.any { v.startsWith(it) } -> BasebandVendor.EXYNOS
            UNISOC_PREFIXES.any { v.startsWith(it) } -> BasebandVendor.UNISOC
            else -> BasebandVendor.UNKNOWN
        }
    }

    /**
     * 只问路径在不在。
     *
     * File.exists() 底层是一次 stat，不会 open —— 这正是这一版的硬约束：AT 节点一旦被
     * open 就会从 RIL 手里抢走 modem 串口。
     */
    private fun existsOnly(path: String): Boolean =
        runCatching { File(path).exists() }.getOrDefault(false)

    /**
     * 在 /vendor/lib64 里找 libqmi* / libqcrilNr*。
     *
     * 读目录列表不是读设备节点，不会惊动 modem；目录不存在或没权限按「没命中」处理。
     */
    private fun listQualcommLibs(): List<String> {
        val names = runCatching { File("/vendor/lib64").list() }.getOrNull().orEmpty()
        return names.filter { name -> QUALCOMM_LIB_PREFIXES.any { name.startsWith(it) } }
            .sorted()
            .take(3)
            .map { name -> "/vendor/lib64/" + name }
    }

    /**
     * 服务是否注册在 ServiceManager 里。
     *
     * 只调 getService：它只查注册表，不跨 binder 发请求；invoke 之类的远端调用一律不碰。
     */
    @SuppressLint("PrivateApi")
    private fun hasService(name: String): Boolean {
        val clazz = Class.forName("android.os.ServiceManager")
        val method = clazz.getDeclaredMethod("getService", String::class.java)
        return method.invoke(null, name) != null
    }

    @SuppressLint("PrivateApi")
    private fun readProp(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("get", String::class.java)
        method.invoke(null, key) as? String ?: ""
    }.getOrDefault("")

    // ---------------- 缓存 ----------------

    /** 上次成功探测的结论；没有、过期或解析失败都返回 null（调用方按「需要重测」处理）。 */
    fun cached(): BasebandProfile? {
        val raw = runCatching { ConfigState.string(KEY_PROFILE, "") }.getOrDefault("")
        if (raw.isBlank()) return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (obj.optLong("expiresAt", 0L) <= System.currentTimeMillis()) return null
        return parseProfile(obj)
    }

    /**
     * 手动重测入口：清掉缓存，下一次 detect 会真的重新探一遍。
     *
     * 空串而不是 null：缓存判空只有一个口径（blank 即无缓存），不必再关心
     * PrefsStore 对 null 是删除还是写空值。
     */
    fun invalidate() {
        runCatching { ConfigState.set(KEY_PROFILE, "") }
    }

    private fun save(profile: BasebandProfile) {
        runCatching {
            val obj = JSONObject()
                .put("vendor", profile.vendor.name)
                .put("confidence", profile.confidence.name)
                .put("evidence", JSONArray(profile.evidence))
                .put("atNodes", JSONArray(profile.atNodes))
                .put("capabilities", JSONArray(profile.capabilities.map { it.name }))
                .put("expiresAt", System.currentTimeMillis() + CACHE_TTL_MS)
            ConfigState.set(KEY_PROFILE, obj.toString())
        }.onFailure { e ->
            LogStore.warn(TAG, "基带结论落盘失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun parseProfile(obj: JSONObject): BasebandProfile? {
        val vendor = runCatching { BasebandVendor.valueOf(obj.optString("vendor", "")) }
            .getOrNull() ?: return null
        val confidence = runCatching { Confidence.valueOf(obj.optString("confidence", "")) }
            .getOrNull() ?: return null
        val capabilities = runCatching {
            val arr = obj.optJSONArray("capabilities") ?: JSONArray()
            (0 until arr.length()).mapNotNull { index ->
                runCatching { RadioCapability.valueOf(arr.optString(index, "")) }.getOrNull()
            }.toSet()
        }.getOrDefault(emptySet())
        return BasebandProfile(
            vendor = vendor,
            confidence = confidence,
            evidence = readStringList(obj, "evidence"),
            atNodes = readStringList(obj, "atNodes"),
            capabilities = capabilities,
        )
    }

    private fun readStringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { index -> arr.optString(index, "").takeIf { it.isNotBlank() } }
    }
}
