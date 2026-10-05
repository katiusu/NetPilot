package com.katiusu.netpilot.core.priv

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.telephony.SubscriptionManager

/**
 * Android 11+ 的「允许的网络类型」**权威存储**。
 *
 * 它不是 `Settings.Global.preferred_network_mode`，而是 TelephonyProvider 里 `siminfo` 表的
 * `allowed_network_types` 列 —— 建表语句见 AOSP main TelephonyProvider.java 的
 * `COLUMN_ALLOWED_NETWORK_TYPES + " BIGINT DEFAULT -1,"`，URI 注册在同文件的
 * `addURI("telephony", "siminfo", URL_SIMINFO)` 与 `addURI("telephony", "siminfo/#", URL_SIMINFO_USING_SUBID)`
 * （`#` 是 subId）。两个列名字面量已在真机 `framework.jar` 的 dex 字符串池里核实过。
 *
 * **为什么值得单独拎出来**：`settings put global preferred_network_mode` 写的是一个遗留兼容字段，
 * 写完用 `settings get` 回读必然一致（自己写自己读）；系统真正读的是上面那一列。所以
 * 「回读通过了，制式却没变」在结构上就无法被 settings 那条路径发现 —— 必须有一个能读**另一个源**
 * 的地方，才谈得上交叉验证。
 *
 * 本对象的边界很窄：只负责「生成读写命令 / 解析原始输出 / 把结果如实分类」，**不做任何判定**，
 * 也不参与降级与恢复的语义（那是 ControlManager 的事）。
 */
object AuthStore {

    /** `content` 命令在 Android 上一直是 `/system/bin/content`，用绝对路径避免 PATH 差异。 */
    const val CONTENT_BIN = "/system/bin/content"

    const val AUTHORITY = "telephony"
    const val PATH_SIMINFO = "siminfo"
    const val CONTENT_URI_STRING = "content://$AUTHORITY/$PATH_SIMINFO"

    /** `siminfo` 的主键列，就是 `SubscriptionInfo.getSubscriptionId()` 的那个 subId。 */
    const val COLUMN_SUB_ID = "sub_id"

    /** AOSP 的列名；少数固件用过单数形式，按顺序试探。 */
    const val COLUMN_ALLOWED_NETWORK_TYPES = "allowed_network_types"

    /** 卡槽序号（`sim_id`）。只在枚举整张表时用来看「这一行是哪张卡」，不参与读写判定。 */
    const val COLUMN_SIM_ID = "sim_id"

    val CANDIDATE_COLUMNS = listOf(COLUMN_ALLOWED_NETWORK_TYPES, "allowed_network_type")

    /** 建表默认值：读到它表示「这一行没有记录过」，而不是「被限制成 0 种制式」。 */
    const val UNSET_VALUE = -1L

    private val SIMINFO_URI: Uri = Uri.parse(CONTENT_URI_STRING)

    // ── 结果类型：宁可多分几类，也不要把「没读到」和「读到但是空」混成一句「未知」 ──

    sealed interface Read {
        /** 真的读到了一个正数位掩码。 */
        data class Value(val networkTypes: Long, val column: String) : Read

        /** 这一行在，但该列为空 / 等于 [UNSET_VALUE]。 */
        data class Unset(val column: String) : Read

        /**
         * 这个 subId 在 `siminfo` 里没有行。
         *
         * `detail` 是 1.5.1 补的：光说「没有这个 subId 的行」用户没法往下分析 —— 得同时讲清
         * 「整张表里现在有哪些行」（[listCommand] 枚举出来的）和「框架给的 subId 从哪来」，
         * 才能真正区分「本机没插卡」与「目标 subId 取错了」。null 时才回落到旧文案。
         */
        data class NoRow(val detail: String? = null) : Read

        /** 被权限拦下（SecurityException）。 */
        data class Denied(val reason: String) : Read

        /** 命令跑不通、列不存在、没有输出等。 */
        data class Unavailable(val reason: String) : Read
    }

    sealed interface Write {
        data object Ok : Write
        data class Denied(val reason: String) : Write
        data class Failed(val reason: String) : Write

        /** 写进去了，但写后回读拿到的不是目标值。 */
        data class Mismatch(val actual: Long, val expected: Long) : Write

        /** 无法确认（回读本身失败）。 */
        data class NotVerified(val reason: String) : Write
    }

    // ── root 通道的命令形态（`su` 里执行，不受应用进程权限限制） ──

    /** `content query --uri content://telephony/siminfo --projection sub_id:<column> --where sub_id=<id>`。 */
    fun queryCommand(column: String, subId: Int): String {
        val command = RootShell.quote(
            listOf(
                CONTENT_BIN, "query",
                "--uri", CONTENT_URI_STRING,
                "--projection", "$COLUMN_SUB_ID:$column",
                "--where", "$COLUMN_SUB_ID=$subId"
            )
        )
        // 详细日志（1.5.0 补）：把真正要执行的命令原文记下来。「命令拼错了」与「provider 拒绝了」
        // 在日志里长得一模一样（都是「没读到」），只有原文能区分这两件事。
        WriteDiag.detail("权威存储查询命令：$command")
        return command
    }

    /**
     * 枚举命令：**不带 `--where`**，把整张 `siminfo` 表读回来。
     *
     * 为什么要它：`--where sub_id=<n>` 查不到行时 `content` 只回一句 `No result found`，
     * 日志里就只剩「没有这个 subId 的行」—— 而真正需要知道的是「表里到底有哪些 sub_id」：
     * 是根本没插卡，还是传进来的 subId 取错了。1.5.1 起读不到时补一次枚举，把表内容记进日志。
     */
    fun listCommand(): String = RootShell.quote(
        listOf(
            CONTENT_BIN, "query",
            "--uri", CONTENT_URI_STRING,
            "--projection", "$COLUMN_SUB_ID:$COLUMN_SIM_ID:$COLUMN_ALLOWED_NETWORK_TYPES"
        )
    )

    /** 枚举回来的一行：只保留诊断需要的三列。 */
    data class SimInfoRow(val subId: Int, val simId: Int, val allowedNetworkTypes: Long)

    /**
     * 解析枚举输出。逐行扫、遇到 `键=值` 才取值 —— 与 [parseQueryOutput] 同样的宽容：
     * 第一字段会被 `Row: 0 ` 前缀污染，所以键取「按空格切开后的最后一段」；
     * 没给 projection 的列自然缺席，不做任何假设。
     */
    fun parseSimInfoRows(output: String): List<SimInfoRow> {
        val rows = mutableListOf<SimInfoRow>()
        for (line in output.lineSequence()) {
            if (line.isBlank()) continue
            val fields = mutableMapOf<String, String>()
            for (field in line.split(',')) {
                val parts = field.split('=', limit = 2)
                if (parts.size != 2) continue
                fields[parts[0].trim().substringAfterLast(' ')] = parts[1].trim()
            }
            val subId = fields[COLUMN_SUB_ID]?.toIntOrNull() ?: continue
            rows += SimInfoRow(
                subId = subId,
                simId = fields[COLUMN_SIM_ID]?.toIntOrNull() ?: -1,
                allowedNetworkTypes = fields[COLUMN_ALLOWED_NETWORK_TYPES]?.toLongOrNull() ?: UNSET_VALUE
            )
        }
        return rows
    }

    /** 把枚举结果压成一行，直接进日志与设置页。 */
    fun describeRows(rows: List<SimInfoRow>): String =
        if (rows.isEmpty()) "（空表）"
        else rows.joinToString("、") {
            "sub_id=${it.subId}(sim_id=${it.simId}, allowed_network_types=${it.allowedNetworkTypes})"
        }

    /**
     * 框架侧「当前可能有卡」的 subId 候选，按可信度排序。
     *
     * 为什么需要：`getDefaultDataSubscriptionId()` 在「默认数据卡还没定」时返回 -1，
     * 而 `-1` 拿去查 `siminfo` 永远是 `No result found` —— 日志里就变成「读不到」，
     * 看起来像 provider 的问题，其实是目标 subId 本身是空的。1.5.1 起把候选一起报出来。
     *
     * 只用**静态**的公开 API 是本方法的硬约束：`getActiveSubscriptionInfoList()` 是实例方法
     * （要 `SubscriptionManager.from(context)`，本对象只处理命令行与判定，不持有 Context），
     * `getActiveSubscriptionIdList()` 干脆是隐藏 API，两者都拿不到。
     * 每一步都 runCatching：这些 API 在缺 READ_PHONE_STATE / 无卡时都会抛。
     */
    fun candidateSubIds(): List<Int> {
        val ids = linkedSetOf<Int>()
        runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrNull()
            ?.let { if (it >= 0) ids.add(it) }
        runCatching { SubscriptionManager.getDefaultVoiceSubscriptionId() }.getOrNull()
            ?.let { if (it >= 0) ids.add(it) }
        runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrNull()
            ?.let { if (it >= 0) ids.add(it) }
        return ids.toList()
    }

    /** `content update --uri ... --where sub_id=<id> --bind allowed_network_types:l:<mask>`。 */
    fun updateCommand(networkTypes: Long, subId: Int): String {
        val command = RootShell.quote(
            listOf(
                CONTENT_BIN, "update",
                "--uri", CONTENT_URI_STRING,
                "--where", "$COLUMN_SUB_ID=$subId",
                "--bind", "$COLUMN_ALLOWED_NETWORK_TYPES:l:$networkTypes"
            )
        )
        WriteDiag.detail("权威存储更新命令：$command")
        return command
    }

    /**
     * 解析 `content query` 的文本输出。
     *
     * 真实形态是每行 `Row: 0 sub_id=1, allowed_network_types=9` —— 第一个字段的键被
     * `Row: <n> ` 前缀污染过，所以键取「按空格切开后的最后一段」。
     */
    fun parseQueryOutput(output: String, column: String): Long? {
        for (line in output.lineSequence()) {
            for (field in line.split(',')) {
                val parts = field.split('=', limit = 2)
                if (parts.size != 2) continue
                if (parts[0].trim().substringAfterLast(' ') != column) continue
                return parts[1].trim().toLongOrNull()
            }
        }
        return null
    }

    /**
     * 把一次命令的原始结果分类成一条 [Read]；判据只有「输出里到底有什么」。
     *
     * 分类结果连同（失败时的）原始输出一起进详细日志：上层只会说「读不到」，
     * 而「没有这一行 / 列不存在 / 被权限拦下 / 命令没输出」是在这里才第一次被区分开的。
     */
    fun classifyQuery(code: Int, stdout: String, stderr: String, column: String): Read {
        val read = classifyQueryRaw(code, stdout, stderr, column)
        val decided = read is Read.Value || read is Read.Unset
        WriteDiag.detail(
            "权威存储查询分类：列=$column exit=$code -> ${describeRead(read)}" +
                if (decided) "" else "；stdout=${stdout.trim().take(160)} stderr=${stderr.trim().take(160)}"
        )
        return read
    }

    private fun classifyQueryRaw(code: Int, stdout: String, stderr: String, column: String): Read {
        callerHint(stderr)?.let {
            return Read.Unavailable("$column: $it（原文：${stderr.trim().take(160)}）")
        }
        if (isDenied(stderr)) return Read.Denied(stderr.trim().take(160))
        val text = stdout.trim()
        if (text.isEmpty()) {
            return Read.Unavailable(
                "命令没有输出（exit=$code" + (if (stderr.isBlank()) "" else "，" + stderr.trim().take(120)) + "）"
            )
        }
        if (text.contains("No result found", ignoreCase = true)) return Read.NoRow(null)
        val value = parseQueryOutput(text, column)
            ?: return Read.Unavailable("输出里找不到 $column 列（${text.take(120)}）")
        return if (value <= 0L) Read.Unset(column) else Read.Value(value, column)
    }

    /** 把一次 `content update` 的原始结果分类。**退出码 0 只说明命令跑通**。 */
    fun classifyUpdate(code: Int, stdout: String, stderr: String): Write {
        val write = classifyUpdateRaw(code, stdout, stderr)
        WriteDiag.detail("权威存储更新分类：exit=$code -> ${describeWrite(write)}")
        return write
    }

    private fun classifyUpdateRaw(code: Int, stdout: String, stderr: String): Write {
        callerHint(stderr)?.let {
            return Write.Failed("$it（原文：${stderr.trim().take(160)}）")
        }
        if (isDenied(stderr)) return Write.Denied(stderr.trim().take(160))
        if (code != 0) {
            return Write.Failed("更新被拒：exit=$code out=${stdout.trim().take(80)} err=${stderr.trim().take(120)}")
        }
        return Write.Ok
    }

    private fun isDenied(stderr: String): Boolean =
        stderr.contains("SecurityException", ignoreCase = true) ||
            stderr.contains("Permission Denial", ignoreCase = true)

    // ── 「真的缺权限」与「provider 根本不认这个调用方」必须分开 ──

    /**
     * 判据：这条 SecurityException 说的是不是「调用方身份不被接受」，而不是「少某个权限」。
     *
     * 1.5.1 把判据从一条扩成三条。真机日志里出现的原句其实是
     * `Access SIMINFO table from not phone/system UID`，而 1.5.0 只认 AMS 那句
     * `Unable to find app for caller`，于是这条被判成「被拒绝」——把用户引向「去补授权」，
     * 而它根本不是权限问题。三种原文对应三种成因，见各自的常量注释。
     *
     * 返回 null = 判据都不命中，那就按普通「缺权限」处理（[Read.Denied] / [Write.Denied]）。
     */
    private fun callerHint(message: String): String? = when {
        message.contains("Access SIMINFO table from not phone/system UID", ignoreCase = true) ->
            HINT_UID_WHITELIST
        message.contains("No permission to access SIMINFO table", ignoreCase = true) ->
            HINT_SIMINFO_DB_PERMISSION
        message.contains("Unable to find app for caller", ignoreCase = true) ||
            message.contains("when getting content provider", ignoreCase = true) -> HINT_NO_APP_RECORD
        else -> null
    }

    /**
     * `Access SIMINFO table from not phone/system UID` —— provider 里的硬编码 UID 名单。
     *
     * AOSP `TelephonyProvider.checkPermissionForSimInfoTable()` 先调
     * `ensureCallingFromSystemOrPhoneUid("Access SIMINFO table from not phone/system UID")`，
     * 判定是 `TelephonyPermissions.isSystemOrPhone(uid) || UserHandle.isSameApp(uid, Process.ROOT_UID)`
     * —— 源码里 root 那一支的注释是「Allow ROOT for testing. ROOT can access underlying DB files anyways.」。
     * 所以 siminfo 只认 system(1000) / phone(1001) / root(0)：应用进程（uid=10xxx）与
     * Shizuku 的 shell（2000）都在名单外。
     *
     * 关键结论：这是 provider 代码里的**身份判断**，不是权限 —— 补授权改不了它。
     */
    private const val HINT_UID_WHITELIST =
        "TelephonyProvider 的 siminfo 表只对 system(1000)/phone(1001)/root(0) 开放（provider 里的硬编码 uid 名单，" +
            "不是可以授予的权限），本通道进程不在名单里；补授权无效 —— 读写权威存储只能用 Root 通道"

    /**
     * `No permission to access SIMINFO table` —— 过了 uid 名单之后的第二道门。
     *
     * 同函数里紧跟其后的是 `checkCallingOrSelfPermission("android.permission.ACCESS_TELEPHONY_SIMINFO_DB")`，
     * 那是 signature|privileged 级权限，只有系统/电话进程拿得到。
     */
    private const val HINT_SIMINFO_DB_PERMISSION =
        "即使过了 uid 名单，provider 还要求 android.permission.ACCESS_TELEPHONY_SIMINFO_DB" +
            "（signature|privileged，只有系统/电话进程拿得到），补授权无效 —— 读写权威存储只能用 Root 通道"

    /**
     * `Unable to find app for caller … when getting content provider …` —— AMS 侧的问题。
     *
     * `ContentResolver.acquireProvider` 会先让 AMS 按**调用方 pid** 找一条应用进程记录
     * （`getRecordForApp`），找不到直接抛 SecurityException；Shizuku 守护进程用 `app_process`
     * 拉起的用户服务进程从未 `attachApplication`，AMS 侧没有它的记录。1.5.0 真机日志实测：
     *
     * ```
     * shizuku 权威存储读取：subId=1 -> 被拒绝：allowed_network_types: Unable to find app for caller
     *   android.app.IApplicationThread$Stub$Proxy@a67e245 (pid=28246) when getting content provider telephony
     * ```
     *
     * 与权限无关（给多少权限都过不去）。Root 通道没这个问题：它走 `/system/bin/content`，
     * `cmd content` 内部用的是隐藏 API `IActivityManager.getContentProviderExternal`，
     * 那个入口不需要应用进程记录。
     */
    private const val HINT_NO_APP_RECORD =
        "AMS 按调用方 pid 找不到应用进程记录（Shizuku 用户服务由 app_process 拉起、从未 attachApplication）；" +
            "与权限无关，补授权无效 —— 改用 Root 通道（它走 /system/bin/content，用的是 getContentProviderExternal，" +
            "不需要应用进程记录）"

    private fun deniedRead(column: String, failure: SecurityException): Read {
        val message = failure.message.orEmpty()
        val hint = callerHint(message)
        return if (hint != null) Read.Unavailable("$column: $hint（原文：$message）")
        else Read.Denied("$column: $message")
    }

    private fun deniedWrite(failure: SecurityException): Write {
        val message = failure.message.orEmpty()
        val hint = callerHint(message)
        return if (hint != null) Write.Failed("$hint（原文：$message）")
        else Write.Denied(message)
    }

    // ── Shizuku 通道的 ContentResolver 实现（uid 是 shell/root，与应用进程不同） ──

    /** 走 ContentResolver 读；结果连原始分类一起进详细日志。 */
    fun read(resolver: ContentResolver?, subId: Int): Read {
        val read = readRaw(resolver, subId)
        WriteDiag.detail("权威存储读取（ContentResolver）：subId=$subId -> ${describeRead(read)}")
        return read
    }

    private fun readRaw(resolver: ContentResolver?, subId: Int): Read {
        if (resolver == null) return Read.Unavailable("没有拿到 ContentResolver")
        var last: Read = Read.Unavailable("没有可用的列")
        for (column in CANDIDATE_COLUMNS) {
            try {
                val cursor = resolver.query(
                    SIMINFO_URI,
                    arrayOf(COLUMN_SUB_ID, column),
                    "$COLUMN_SUB_ID=?",
                    arrayOf(subId.toString()),
                    null
                )
                cursor?.use {
                    if (!it.moveToFirst()) {
                        last = Read.NoRow(
                            "sub_id=$subId 在 siminfo 表里没有行（ContentResolver 这条路径只做单行查询，" +
                                "不枚举整张表；要看表里到底有什么，只能用 Root 通道）"
                        )
                    } else {
                        val index = it.getColumnIndex(column)
                        if (index < 0) {
                            last = Read.Unavailable("$column 列不存在")
                        } else {
                            val raw = if (it.isNull(index)) UNSET_VALUE else it.getLong(index)
                            return if (raw <= 0L) Read.Unset(column) else Read.Value(raw, column)
                        }
                    }
                }
            } catch (denied: SecurityException) {
                return deniedRead(column, denied)
            } catch (failure: Exception) {
                last = Read.Unavailable("$column: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
        return last
    }

    /** 走 ContentResolver 写；调用方负责写后回读（这里只回答「update 收到了什么」）。 */
    fun write(resolver: ContentResolver?, subId: Int, networkTypes: Long): Write {
        val write = writeRaw(resolver, subId, networkTypes)
        WriteDiag.detail(
            "权威存储写入（ContentResolver）：subId=$subId 目标=$networkTypes -> ${describeWrite(write)}"
        )
        return write
    }

    private fun writeRaw(resolver: ContentResolver?, subId: Int, networkTypes: Long): Write {
        if (resolver == null) return Write.Failed("没有拿到 ContentResolver")
        val values = ContentValues().apply { put(COLUMN_ALLOWED_NETWORK_TYPES, networkTypes) }
        return try {
            val rows = resolver.update(SIMINFO_URI, values, "$COLUMN_SUB_ID=?", arrayOf(subId.toString()))
            if (rows > 0) Write.Ok
            else Write.Failed("update 影响 0 行（sub_id=$subId 没有对应行，或该列不可写）")
        } catch (denied: SecurityException) {
            deniedWrite(denied)
        } catch (failure: Exception) {
            Write.Failed("${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    // ── 展示与跨 binder 的单行编码 ──

    fun describeRead(read: Read): String = when (read) {
        is Read.Value -> "= ${read.networkTypes}（列 ${read.column}）"
        is Read.Unset -> "未设置（列 ${read.column} 为空或为建表默认值 $UNSET_VALUE）"
        is Read.NoRow -> read.detail ?: "没有这个 subId 的行"
        is Read.Denied -> "被拒绝：${read.reason}"
        is Read.Unavailable -> "读不到：${read.reason}"
    }

    fun describeWrite(write: Write): String = when (write) {
        Write.Ok -> "成功"
        is Write.Denied -> "被拒绝：${write.reason}"
        is Write.Failed -> "失败：${write.reason}"
        is Write.Mismatch -> "写入后回读为 ${write.actual}，与目标 ${write.expected} 不一致"
        is Write.NotVerified -> "无法确认：${write.reason}"
    }

    /**
     * 为什么用单行字符串跨 binder 传结果，而不是自定义 Parcelable：
     * AIDL 面越小，越不容易因为签名漂移让整条通道失效（这个工程已经吃过 ITelephony 漂移的亏）。
     */
    fun encodeRead(read: Read): String = when (read) {
        is Read.Value -> "VALUE|${read.networkTypes}|${read.column}"
        is Read.Unset -> "UNSET|${read.column}"
        is Read.NoRow -> if (read.detail == null) "NOROW" else "NOROW|" + read.detail.replace('|', '/')
        is Read.Denied -> "DENIED|" + read.reason.replace('|', '/')
        is Read.Unavailable -> "UNAVAILABLE|" + read.reason.replace('|', '/')
    }

    fun decodeRead(raw: String): Read {
        val parts = raw.split('|')
        return when (parts.getOrNull(0)) {
            "VALUE" -> {
                val value = parts.getOrNull(1)?.toLongOrNull()
                if (value == null) Read.Unavailable(raw) else Read.Value(value, parts.getOrNull(2).orEmpty())
            }
            "UNSET" -> Read.Unset(parts.getOrNull(1).orEmpty())
            "NOROW" -> Read.NoRow(parts.drop(1).joinToString("|").ifEmpty { null })
            "DENIED" -> Read.Denied(parts.drop(1).joinToString("|"))
            "UNAVAILABLE" -> Read.Unavailable(parts.drop(1).joinToString("|"))
            else -> Read.Unavailable(raw)
        }
    }

    fun encodeWrite(write: Write): String = when (write) {
        Write.Ok -> "OK"
        is Write.Denied -> "DENIED|" + write.reason.replace('|', '/')
        is Write.Failed -> "FAILED|" + write.reason.replace('|', '/')
        is Write.Mismatch -> "MISMATCH|${write.actual}|${write.expected}"
        is Write.NotVerified -> "NOTVERIFIED|" + write.reason.replace('|', '/')
    }

    fun decodeWrite(raw: String): Write {
        val parts = raw.split('|')
        return when (parts.getOrNull(0)) {
            "OK" -> Write.Ok
            "DENIED" -> Write.Denied(parts.drop(1).joinToString("|"))
            "FAILED" -> Write.Failed(parts.drop(1).joinToString("|"))
            "MISMATCH" -> Write.Mismatch(
                parts.getOrNull(1)?.toLongOrNull() ?: -1L,
                parts.getOrNull(2)?.toLongOrNull() ?: -1L
            )
            "NOTVERIFIED" -> Write.NotVerified(parts.drop(1).joinToString("|"))
            else -> Write.Failed(raw)
        }
    }
}
