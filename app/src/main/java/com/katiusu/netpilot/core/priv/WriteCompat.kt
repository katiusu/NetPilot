package com.katiusu.netpilot.core.priv

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import com.katiusu.netpilot.core.monitor.CarrierInfo
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.prefs.ConfigState
import com.katiusu.netpilot.util.SystemVersionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 特权写入的「系统兼容性」：一个可配置的回读校验开关 + 一段只读的设备/通道信息。
 *
 * **为什么不按厂商分支**（参考项目 Network_Enhance 有一大堆「小米要写 nr_sa_mode、
 * vivo 跳过、三星/华为 PNM 写入可能被忽略」的分支）：那些分支是在一台台真机上试出来的，
 * 而本应用写的是 AOSP 的 `settings put global preferred_network_mode[1]`（不是 `nr_sa_mode`），
 * 在拿不到对应真机验证的前提下硬猜厂商分支，只会给正常机型引入新 bug。
 * 所以这里只做「防 bug 的那半边」：把写入后的回读校验做成可关，并把机型信息摊开给用户看。
 * 真遇到「写入被系统忽略」的机型，回读校验会把它标为失败并写进日志页，可查、可定位。
 */
object WriteVerification {

    /** 存储键。默认 **true**：保持既有的回读校验行为，不改变任何现网机型的判定口径。 */
    const val KEY_VERIFY_WRITE = "np_verify_write"
    const val DEFAULT_VERIFY_WRITE = true

    private const val TAG = "写入校验"

    /**
     * 是否在 `settings put` 之后回读校验。
     *
     * 读不到 context（理论上不该发生）时按默认值 true 处理：**校验是安全侧** ——
     * 宁可误判一次失败并写日志，也不要静默地把「其实没写进去」当成成功。
     */
    fun enabled(context: Context?): Boolean {
        val app = context?.applicationContext ?: return DEFAULT_VERIFY_WRITE
        runCatching { ConfigState.init(app) }
        return runCatching { ConfigState.bool(KEY_VERIFY_WRITE, DEFAULT_VERIFY_WRITE) }
            .getOrDefault(DEFAULT_VERIFY_WRITE)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching { ConfigState.init(context.applicationContext) }
        ConfigState.set(KEY_VERIFY_WRITE, enabled)
        LogStore.info(
            TAG,
            if (enabled) {
                "已开启写入后回读校验：写完 settings 会读回来比对"
            } else {
                "已关闭写入后回读校验：settings put 写一次即视为成功（回读不准的机型用这个）"
            },
        )
    }
}

/**
 * 只读的系统兼容性信息，给设置页「系统兼容性」卡片展示。
 *
 * 分两半，故意分开：
 * - [read]：**不需要 IO** 的静态信息（`Build` / 属性反射），可直接在组合里调用；
 * - [probe]：**必须真跑一遍**才能得到的值（当前通道、会执行的写入命令、读取探测），
 *   所以是 suspend，内部走 [Dispatchers.IO]（要起 `su` / `app_process`，最长十几秒），
 *   由设置页在页面级后台加载、期间显示「检测中…」。
 *
 * 两项原则：
 * 1. 任何一项失败就是空串 / [WriteStage.NONE] / [ReadState.SKIPPED]，由 UI 渲染成「未知」，
 *    绝不能因为展示信息把设置页搞崩；
 * 2. **绝不能为了好看编一个固定值** —— 用户要的是「这台机器现在到底会怎么写」。
 */
object SystemCompatInfo {

    /**
     * 不需要 IO 的静态信息。
     *
     * [osVersion] 是当前真实系统版本（取值顺序见 [SystemVersionDetector.getOsVersion]）。
     * 任何一项读不到都是空串，由 UI 渲染成「未知」。
     */
    data class Info(
        val vendor: String,
        val model: String,
        val marketName: String,
        val android: String,
        val osVersion: String,
    )

    /** 写入真正会落到哪条分支。UI 据此给出**由真实参数拼出来的**命令形态。 */
    enum class WriteStage {
        /** Root：`app_process` + [PrivilegedCli] `setmode`（内部先走 ITelephony 反射）。 */
        APP_PROCESS_CLI,

        /** Root，但 `app_process` 那条路没跑通，退到 `settings put`。 */
        SETTINGS_FALLBACK,

        /** Shizuku：直接 binder 调用，不起 shell。 */
        SHIZUKU_BINDER,

        /** 没有可用通道 / 没确定写入目标，不会发生任何写入。 */
        NONE,
    }

    /** 读取探测的结果。后两种**不是失败，是没有执行**，必须和「失败」区分开。 */
    enum class ReadState {
        /** 真的执行了读取并拿到了值。 */
        OK,

        /** 真的执行了读取，但失败、超时或拒绝。 */
        FAILED,

        /** 没有可用通道，没有执行读取。 */
        SKIPPED,

        /** 没能确定写入目标，没有执行读取。 */
        NO_TARGET,
    }

    /**
     * 一次真实探测的结果。
     *
     * 所有字段都来自实际执行；测不到就是空串 / [WriteStage.NONE] / [ReadState.SKIPPED]，
     * UI 一律显示「未知」—— 这里绝不会为了好看编一个固定值出来。
     */
    data class Probe(
        /** 实际生效的写入通道短名（`Root` / `Shizuku`）；空串 = 未检测到可用通道。 */
        val channelLabel: String,
        /** 通道不可用时，两条通道各自的失败原因（来自 [ControlManager.statuses]）。 */
        val channelReason: String,
        val stage: WriteStage,
        /** 由通道 + 真实写入目标拼出来的完整命令；推导不出来是空串。 */
        val writeCommand: String,
        /** 写入的目标键（如 `preferred_network_mode1`）；推导不出来是空串。 */
        val writeKey: String,
        /** 写入目标 subId；-1 = 推导不出来。 */
        val targetSubId: Int,
        val readState: ReadState,
        /** 读取成功时的原始值（未做任何修饰）。 */
        val readRaw: String,
        /** 读取失败时的原始 stderr / 说明；空串由 UI 补一句通用解释。 */
        val readNote: String,
        /**
         * 权威存储（TelephonyProvider `siminfo.allowed_network_types`）的读取结果，一行文本。
         *
         * 为什么要它：这是「回读通过却切不动」唯一能给交叉证据的地方 —— 上面的 [readState]
         * 读的是 `Settings.Global.preferred_network_mode`，一个写完回读必然一致的遗留兼容字段。
         */
        val authStore: String,
        /** 本机 `ITelephony` 上实际存在哪几条写入方法（Android 14 起只剩一条）。 */
        val writeMethods: String,
        /**
         * 本机识别到的运营商 + 它对应的默认制式，一行文本（1.5.0 新增）。
         *
         * 为什么要它：`NetworkMode.carrierDefault` 的 MCC/MNC 表在 1.5.0 按四个公开来源重写过，
         * 而这张表直接决定「解除锁 5G 时回到哪个制式」。把识别结果摊在设置页，换张卡就能立刻
         * 看出表里有没有这张卡 —— 否则表错了也永远是个看不见的猜测。
         */
        val carrierInfo: String,
    )

    private const val UNKNOWN = "" // 空串由 UI 渲染成「未知」，这里不引入资源依赖

    private const val APP_PROCESS = "app_process"
    private const val APP_PROCESS_BIN = "/system/bin"
    private const val MAIN_CLASS = "com.katiusu.netpilot.core.priv.PrivilegedCli"
    private const val MODE_PREFIX = "MODE "
    private const val MAX_RAW_CHARS = 120

    /**
     * 静态信息，不需要 IO。
     *
     * [context] 现在只用于 hidden API 豁免前的兜底（参数保留是为了不破坏既有调用方）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun read(context: Context): Info {
        // 读 system property 走 hidden API：先触发工程已有的一次性豁免（读不到也无所谓，
        // 拿不到属性只是显示「未知」，不影响任何功能）。
        runCatching { TelephonyReflection.ensureHiddenApiExempted() }
        return Info(
            vendor = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            marketName = runCatching { SystemVersionDetector.getMarketName() }.getOrDefault(UNKNOWN),
            android = runCatching { SystemVersionDetector.getAndroidVersion() }
                .getOrDefault("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"),
            // 「OS 版本」：厂商 OS 版本优先，读不到依次退到构建增量号、Android 版本（见 getOsVersion）
            osVersion = runCatching { SystemVersionDetector.getOsVersion() }.getOrDefault(UNKNOWN),
        )
    }

    /**
     * 真跑一遍探测：当前通道、会执行的写入命令、读取探测结果。
     *
     * 为什么每次都真跑，而不是读缓存：用户要的就是「这台机器**现在**到底会怎么写」，
     * 缓存值可能来自几个小时前的另一次探测（比如那之后 Shizuku 被关了）。
     */
    suspend fun probe(context: Context): Probe = withContext(Dispatchers.IO) {
        val app = context.applicationContext ?: context

        // statuses() 内部只做一次完整探测（就是 acquire()），并把两条通道各自的失败原因留在
        // 返回值里；紧随其后的 cached() 是纯内存读取。这样「没有 root 也没有 Shizuku」的机器
        // 只探测一次 —— 再探一次的代价是十几秒白等。
        val notes = runCatching { ControlManager.statuses() }.getOrDefault(emptyList())
        val channel = ControlManager.cached()
        // 只做方法枚举（不需要任何权限），因此三个分支都能报出「本机实际有几条写入路径」。
        val writeMethods = runCatching { TelephonyReflection.describeWriteMethods() }.getOrDefault(UNKNOWN)

        // 运营商识别放在最前面：即使下面立刻早返回（没有通道 / 没有写入目标），
        // 「这张卡是谁、会回落到哪个制式」也是能读到的 —— 它不需要任何写入权限。
        val subId = runCatching { ControlManager.getDefaultDataSubId() }.getOrDefault(-1)
        val carrierInfo = runCatching { CarrierInfo.activeCarrierSummary(app, subId) }.getOrDefault(UNKNOWN)

        if (channel == null) {
            return@withContext Probe(
                channelLabel = UNKNOWN,
                channelReason = notes
                    .filter { it.note.isNotBlank() }
                    .joinToString("；") { "${it.label}：${it.note}" },
                stage = WriteStage.NONE,
                writeCommand = UNKNOWN,
                writeKey = UNKNOWN,
                targetSubId = -1,
                readState = ReadState.SKIPPED,
                readRaw = UNKNOWN,
                readNote = UNKNOWN,
                authStore = UNKNOWN,
                writeMethods = writeMethods,
                carrierInfo = carrierInfo,
            )
        }

        // 写入目标：当前默认数据卡 → subId → 卡槽，与 RootController.slotSuffix 用同一套映射。
        // 推导不出来就整块显示「未知」，绝不编一个「1」出来 —— 那正是这次要消灭的假值。
        val slot = if (subId >= 0) {
            runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
        } else {
            -1
        }
        val key = if (subId >= 0) {
            if (slot > 0) "preferred_network_mode$slot" else "preferred_network_mode"
        } else {
            UNKNOWN
        }
        if (key.isEmpty()) {
            return@withContext Probe(
                channelLabel = channel.label,
                channelReason = UNKNOWN,
                stage = WriteStage.NONE,
                writeCommand = UNKNOWN,
                writeKey = UNKNOWN,
                targetSubId = -1,
                readState = ReadState.NO_TARGET,
                readRaw = UNKNOWN,
                readNote = UNKNOWN,
                authStore = UNKNOWN,
                writeMethods = writeMethods,
                carrierInfo = carrierInfo,
            )
        }

        var stage = WriteStage.NONE
        var writeCommand = UNKNOWN
        var readState = ReadState.SKIPPED
        var readRaw = UNKNOWN
        var readNote = UNKNOWN

        when (channel.method) {
            ControlMethod.ROOT -> {
                // 先真跑一次 CLI 读（RootController 的成功路径就是这条）：
                // 跑通 ⇒ 写入也走 CLI；跑不通 ⇒ RootController 自己也会退到 settings，这里如实跟着退。
                // 于是「写入方式」是由一次真实执行推出来的，而不是照抄常量或猜的。
                val cli = runCatching { RootShell.exec(cliCommand(app, "getmode $subId")) }.getOrNull()
                val cliMode = cli?.stdout
                    ?.lineSequence()
                    ?.map { it.trim() }
                    ?.firstOrNull { it.startsWith(MODE_PREFIX) }
                    ?.removePrefix(MODE_PREFIX)
                    ?.trim()
                    ?.toIntOrNull()
                val cliNote = cli?.let { (it.stderr.ifBlank { it.stdout }).trim().take(MAX_RAW_CHARS) }.orEmpty()

                if (cliMode != null && cliMode >= 0) {
                    stage = WriteStage.APP_PROCESS_CLI
                    writeCommand = "su -c \"" + cliCommand(app, "setmode $subId <模式值>") + "\""
                    readState = ReadState.OK
                    readRaw = MODE_PREFIX + cliMode
                } else {
                    stage = WriteStage.SETTINGS_FALLBACK
                    writeCommand = "su -c \"settings put global $key <模式值>\""
                    val result = runCatching { RootShell.exec("settings get global $key") }.getOrNull()
                    if (result == null || result.timedOut) {
                        readState = ReadState.FAILED
                        readNote = cliNote
                    } else {
                        val raw = result.stdout.trim().take(MAX_RAW_CHARS)
                        readRaw = raw
                        readNote = result.stderr.trim().take(MAX_RAW_CHARS).ifBlank { cliNote }
                        readState = if (raw.toIntOrNull() != null) ReadState.OK else ReadState.FAILED
                    }
                }
            }

            ControlMethod.SHIZUKU -> {
                // Shizuku 没有 shell，读的就是它真正会用的那条 binder 调用
                stage = WriteStage.SHIZUKU_BINDER
                writeCommand = "binder: IShizukuController.setNetworkMode($subId, <模式值>)"
                val mode = runCatching { channel.getMode(subId) }.getOrDefault(-1)
                if (mode >= 0) {
                    readState = ReadState.OK
                    readRaw = mode.toString()
                } else {
                    readState = ReadState.FAILED
                }
            }

            // 通道对象存在但方法未知：不做任何假设，全部留空
            ControlMethod.NONE -> Unit
        }

        // 权威存储的读法优先走通道（Root 用 su 执行 content query、Shizuku 用特权进程的
        // ContentResolver）；通道给不出真值时再由应用进程自己查一次，并把两条路径各自的原因都写出来。
        val authStore = runCatching { describeAuthStore(channel, subId, app) }.getOrDefault(UNKNOWN)

        Probe(
            channelLabel = channel.label,
            channelReason = UNKNOWN,
            stage = stage,
            writeCommand = writeCommand,
            writeKey = key,
            targetSubId = subId,
            readState = readState,
            readRaw = readRaw,
            readNote = readNote,
            authStore = authStore,
            writeMethods = writeMethods,
            carrierInfo = carrierInfo,
        )
    }

    /**
     * 读一次权威存储并渲染成一行。
     *
     * 「读不到」和「读到但是空的」是两件不同的事，不能合并成一句「未知」：
     * 前者说明权限或列名有问题，后者说明这张卡从来没被设置过 —— 排查方向完全相反。
     */
    private suspend fun describeAuthStore(
        channel: NetworkControlChannel,
        subId: Int,
        context: Context
    ): String {
        val viaChannel = runCatching { channel.readAuthStore(subId) }.getOrNull()
        if (viaChannel is AuthStore.Read.Value) {
            return "siminfo.${AuthStore.COLUMN_ALLOWED_NETWORK_TYPES} = ${viaChannel.networkTypes}" +
                "（sub_id=$subId，经 ${channel.label} 读回）"
        }
        if (viaChannel is AuthStore.Read.Unset) {
            return "siminfo.${AuthStore.COLUMN_ALLOWED_NETWORK_TYPES} ${AuthStore.describeRead(viaChannel)}" +
                "（sub_id=$subId，经 ${channel.label} 读回）"
        }
        // 1.5.1：行不存在时，Root 通道的读路径会再枚举一次整张表，并把「表里现在有哪些 sub_id」
        // 拼进 detail —— 这一段比「读不到」有用得多，直接端出来（它自己已经带了 sub_id）。
        if (viaChannel is AuthStore.Read.NoRow && !viaChannel.detail.isNullOrBlank()) {
            return viaChannel.detail
        }
        val viaApp = runCatching { AuthStore.read(context.contentResolver, subId) }.getOrNull()
        if (viaApp is AuthStore.Read.Value) {
            return "siminfo.${AuthStore.COLUMN_ALLOWED_NETWORK_TYPES} = ${viaApp.networkTypes}（sub_id=$subId，应用进程直接读回）"
        }
        val channelReason = viaChannel?.let { AuthStore.describeRead(it) } ?: "通道未实现"
        val appReason = viaApp?.let { AuthStore.describeRead(it) } ?: "应用进程未执行"
        return "读不到（sub_id=$subId；${channel.label}：$channelReason；应用进程：$appReason）"
    }

    /**
     * 与 `RootController.runCli` 完全同构的一条 CLI 命令。
     *
     * 这里重新拼一次，而不是去引用 `RootController` 的 private 常量：那是它的实现细节，
     * 既引不到也不该形成隐式耦合。引号规则用的是同一个 [RootShell.quote]，
     * 所以拼出来的字符串和它真正执行的那条一致。
     */
    private fun cliCommand(context: Context, args: String): String {
        val classpath = RootShell.quote(listOf(context.packageCodePath ?: UNKNOWN))
        return "CLASSPATH=$classpath $APP_PROCESS $APP_PROCESS_BIN $MAIN_CLASS $args"
    }
}
