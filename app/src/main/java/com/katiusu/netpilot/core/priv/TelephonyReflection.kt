package com.katiusu.netpilot.core.priv

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.katiusu.netpilot.core.mode.NetworkModeBitmaskMapper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * 通过 `ITelephony` 读写首选网络模式。
 *
 * `android.os.ServiceManager` 与 `com.android.internal.telephony.ITelephony` 都是非 SDK 接口，
 * 不同 Android 版本和 OEM 固件之间签名会漂移：多一个包名参数、返回 `long` 还是 `int`、
 * 甚至整个方法不存在。所以这里全程反射，并把「哪个重载能用」推迟到运行时探测 ——
 * 任何不匹配都只回落成一条日志，而不是让调用方吃 `NoSuchMethodError`。
 *
 * 必须跑在特权进程里（`app_process` 起的 root 服务或 Shizuku 用户服务）：写接口要求
 * MODIFY_PHONE_STATE，应用进程拿不到。
 */
object TelephonyReflection {

    private const val TAG = "NetPilot"

    // 下面这些类名、方法名与豁免前缀是「事实」：它们是隐藏 API 的标识符本身，不能改名。
    private const val SERVICE_MANAGER = "android.os.ServiceManager"
    private const val SUBSCRIPTION_MANAGER = "android.telephony.SubscriptionManager"
    private const val ITELEPHONY_STUB = "com.android.internal.telephony.ITelephony\$Stub"
    private const val PHONE_PKG = "com.android.phone"

    /** subId / 卡槽 / 网络模式共用的失败哨兵，沿用上游 API「-1 表示没有」的约定。 */
    private const val UNAVAILABLE = -1

    /** [describeWriteMethods] 要汇报的三条写入方法，顺序与 [setNetworkMode] 的尝试顺序一致。 */
    private val WRITE_METHODS = arrayOf(
        "setAllowedNetworkTypesForReason",
        "setAllowedNetworkTypes",
        "setPreferredNetworkType"
    )

    private val bitmaskMapper get() = NetworkModeBitmaskMapper.platform
    private val userReason get() = NetworkModeBitmaskMapper.reasonUser

    /**
     * 豁免名单。`Landroid/telephony/` 顺带覆盖 [getDefaultDataSlot] 与 SubscriptionSwitcher 用到的
     * `SubscriptionManager`；`Landroid/os/ServiceManager` 覆盖用来取 telephony binder 的隐藏
     * `ServiceManager.getService`。
     */
    private val EXEMPT_PREFIXES = arrayOf(
        "Landroid/telephony/",
        "Lcom/android/internal/telephony/",
        "Landroid/os/ServiceManager"
    )

    /** `Build.VERSION_CODES.P` 之前还没有非 SDK 接口管控，也就无所谓豁免。 */
    private val enforcementActive get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * 一次性把 telephony 相关包从非 SDK 接口管控里放行。
     *
     * 用 `addHiddenApiExemptions` 而不是 `setHiddenApiExemptions`：后者会把别的库已经登记的
     * 条目一并清空。Shizuku 用户进程本身就在豁免名单里，重复执行无害；`app_process` 起的
     * root 进程没有这个保证，因此两条入口都要先读一次这个值来触发豁免。
     */
    private val exemptionApplied: Boolean by lazy {
        if (!enforcementActive) return@lazy true
        try {
            HiddenApiBypass.addHiddenApiExemptions(*EXEMPT_PREFIXES)
        } catch (failure: Throwable) {
            Log.w(TAG, "隐藏 API 豁免未生效；进程可能本身已被豁免，继续尝试反射", failure)
            false
        }
    }

    /**
     * 触发并返回一次性豁免的结果，供 [SubscriptionSwitcher] 等同样要反射这两个包的调用方复用，
     * 避免各自重复登记。
     */
    fun ensureHiddenApiExempted(): Boolean = exemptionApplied

    // ── 反射原语 ──────────────────────────────────────────────────────────

    /**
     * 取静态方法并调用。
     *
     * 形参类型显式传入而不是从实参反推：隐藏 API 上 `int` 与 `Integer`、`long` 与 `Long`
     * 是两个不同的重载，反推容易挑错。
     */
    private fun staticInvoke(
        className: String,
        methodName: String,
        parameterTypes: List<Class<*>>,
        arguments: List<Any?>
    ): Any? = Class.forName(className)
        .getMethod(methodName, *parameterTypes.toTypedArray())
        .invoke(null, *arguments.toTypedArray())

    /** 解析 telephony 服务的结果：拿到 `ITelephony` 代理，或拿到一句可直接展示的原因。 */
    private sealed interface Binding {
        data class Ready(val stub: Any) : Binding
        data class Broken(val reason: String) : Binding
    }

    private fun describe(failure: Throwable): String = failure.javaClass.simpleName + ": " + failure.message

    /**
     * 分两步解析 `ITelephony`：先 `ServiceManager.getService`，再 `ITelephony.Stub.asInterface`。
     * 两步分别捕获，失败时才能说清卡在哪一步。
     */
    private fun bindTelephony(): Binding {
        val binder = try {
            exemptionApplied // 反射前必须已豁免，读一次即触发
            staticInvoke(
                SERVICE_MANAGER,
                "getService",
                parameterTypes = listOf(String::class.java),
                arguments = listOf(Context.TELEPHONY_SERVICE)
            ) as? IBinder
        } catch (failure: Throwable) {
            return Binding.Broken("调用 ServiceManager.getService 失败：" + describe(failure))
        }
        if (binder == null) {
            return Binding.Broken("ServiceManager.getService(${Context.TELEPHONY_SERVICE}) 返回 null，telephony 服务不可达")
        }

        val stub = try {
            staticInvoke(
                ITELEPHONY_STUB,
                "asInterface",
                parameterTypes = listOf(IBinder::class.java),
                arguments = listOf(binder)
            )
        } catch (failure: Throwable) {
            return Binding.Broken("调用 ITelephony.Stub.asInterface 失败：" + describe(failure))
        }
        return if (stub == null) Binding.Broken("ITelephony.Stub.asInterface 返回 null") else Binding.Ready(stub)
    }

    /** 取 `ITelephony` 代理；失败返回 null，并把原因写进日志。 */
    private fun telephonyStub(caller: String): Any? = when (val binding = bindTelephony()) {
        is Binding.Ready -> binding.stub
        is Binding.Broken -> {
            Log.e(TAG, "$caller 反射获取 ITelephony 失败：${binding.reason}")
            // 1.5.1：这是过程细节（第一步为什么断），归详细诊断开关管；结论由 setNetworkMode
            // 里那条无条件的「没有可用的 ITelephony 通道」warn 承担，默认也看得见。
            WriteDiag.detail("$caller 反射获取 ITelephony 失败：${binding.reason}")
            null
        }
    }

    /**
     * 一个候选重载：匹配条件 + 实参构造 + 返回值转换。
     *
     * [arity] 是形参个数；[typedAt] 中列出的下标必须类型完全相等，用来在同一方法名的多个
     * 重载之间取舍（典型差异是末尾多一个包名 `String`）。[adapt] 在 try 内部转换返回值 ——
     * 转换失败与 invoke 抛异常一样，都表示「这个重载在本机不适用」。
     */
    private class Candidate(
        private val arity: Int,
        private val typedAt: Map<Int, Class<*>> = emptyMap(),
        private val adapt: (Any?) -> Any? = { it },
        private val arguments: () -> Array<Any?>
    ) {
        fun fits(parameterTypes: Array<Class<*>>): Boolean =
            parameterTypes.size == arity && typedAt.all { (index, type) -> parameterTypes[index] == type }

        fun fire(receiver: Any, method: Method): Any? = adapt(method.invoke(receiver, *arguments()))
    }

    /** [dispatch] 的结果：命中了（值可能为 null），或所有候选都不适用。 */
    private sealed interface CallResult {
        data class Hit(val value: Any?) : CallResult
        data object Miss : CallResult
    }

    /**
     * 在 [receiver] 上依次尝试「名为 [target] 的方法 × [candidates]」的每个组合，返回首个成功的调用。
     *
     * 两个维度都必须试：同一方法名可能有多个重载，而同一个重载还可能因为形参类型与本机固件
     * 不符、或者要到 invoke 时才暴露问题而失败。所以一次失败只意味着换下一个组合，绝不外抛。
     */
    private fun dispatch(
        receiver: Any,
        target: String,
        candidates: List<Candidate>,
        trace: ((String) -> Unit)? = null,
        onDenied: ((String) -> Unit)? = null
    ): CallResult {
        var overloads = 0
        for (method in receiver.javaClass.methods) {
            if (method.name != target) continue
            overloads++
            for (candidate in candidates) {
                if (!candidate.fits(method.parameterTypes)) continue
                try {
                    val value = candidate.fire(receiver, method)
                    trace?.invoke("$target(${describeTypes(method.parameterTypes)}) 调用成功 -> $value")
                    return CallResult.Hit(value)
                } catch (failure: Exception) {
                    // 该组合在本机不可用，继续试下一个。
                    // 为什么失败也要留痕：这里的失败原因（多数是 SecurityException:
                    // MODIFY_PHONE_STATE）正是「回读通过却切不动」最关键的证据，
                    // 改造前它被 catch (_: Exception) 完全吞掉，连 logcat 都查不到。
                    // 1.5.0：权限类失败单独交给 onDenied 无条件记录 —— 它不是「某个重载不适用」，
                    // 而是「这个身份根本不允许写」，是两种性质完全不同的失败。
                    permissionDenial(failure)?.let { denial -> onDenied?.invoke("$target $denial") }
                    trace?.invoke("$target(${describeTypes(method.parameterTypes)}) 抛异常 ${describe(failure)}")
                }
            }
        }
        trace?.invoke(
            if (overloads == 0) "$target 在本机 ITelephony 上不存在"
            else "$target 的 $overloads 个重载都不匹配或全部抛异常"
        )
        return CallResult.Miss
    }

    /**
     * 把「权限不够」从一堆失败里认出来，并连当前 uid 一起说清楚。
     *
     * 为什么值得单独做：`MODIFY_PHONE_STATE` 是 `signature|privileged` 权限，`com.android.shell`
     * （Shizuku 无线调试的 uid 2000）**不持有**；而 `settings put` 只需要 shell/root 的
     * `WRITE_SECURE_SETTINGS`。所以「制式完全没切，写入与回读却一路绿灯」是一个可以精确指认的
     * 状态，不该被压成一句笼统的「写入失败」。
     */
    private fun permissionDenial(failure: Throwable): String? {
        var cause: Throwable? = failure
        while (cause != null) {
            if (cause is SecurityException) {
                return "被权限拦下：SecurityException: " + cause.message + "（本进程 uid=" + Process.myUid() +
                    "；写入需要 MODIFY_PHONE_STATE，它是 signature|privileged，当前身份不持有）"
            }
            cause = cause.cause
        }
        return null
    }

    /** 把方法形参类型压成一行短文本，供诊断日志记录「是哪个重载」失败了。 */
    private fun describeTypes(parameterTypes: Array<Class<*>>): String =
        parameterTypes.joinToString(", ") { it.simpleName }

    // ── 五种目标方法各自的形参形态 ────────────────────────────────────────

    /** `getAllowedNetworkTypesForReason(subId, reason[, package])` —— Android 11+ 的位掩码读法。 */
    private fun allowedTypeReads(subId: Int, adapt: (Any?) -> Any? = { it }): List<Candidate> = listOf(
        Candidate(2, adapt = adapt) { arrayOf(subId, userReason) },
        Candidate(3, mapOf(2 to String::class.java), adapt) { arrayOf(subId, userReason, PHONE_PKG) }
    )

    /** `getPreferredNetworkType(subId[, package])` —— Android 10 及更早直接给 RIL 模式。 */
    private fun preferredTypeReads(subId: Int, adapt: (Any?) -> Any? = { it }): List<Candidate> = listOf(
        Candidate(1, adapt = adapt) { arrayOf(subId) },
        Candidate(2, mapOf(1 to String::class.java), adapt) { arrayOf(subId, PHONE_PKG) }
    )

    /** `setAllowedNetworkTypesForReason(subId, reason, bitmask[, package])`。 */
    private fun allowedTypeWrites(subId: Int, networkTypes: Long): List<Candidate> = listOf(
        Candidate(3) { arrayOf(subId, userReason, networkTypes) },
        Candidate(4, mapOf(3 to String::class.java)) { arrayOf(subId, userReason, networkTypes, PHONE_PKG) }
    )

    /**
     * `setAllowedNetworkTypes(subId, bitmask[, package])`。
     *
     * 它与上面那条的第二个形参是 `long`（位掩码）而不是 `int`（reason），这也是区分两者的
     * 唯一依据。
     */
    private fun legacyAllowedTypeWrites(subId: Int, networkTypes: Long): List<Candidate> = listOf(
        Candidate(2) { arrayOf(subId, networkTypes) },
        Candidate(3, mapOf(1 to Long::class.javaPrimitiveType!!)) { arrayOf(subId, networkTypes, PHONE_PKG) }
    )

    /** `setPreferredNetworkType(subId, mode)`，只有这一种形态。 */
    private fun preferredTypeWrites(subId: Int, mode: Int): List<Candidate> = listOf(
        Candidate(2) { arrayOf(subId, mode) }
    )

    // ── 对外接口 ──────────────────────────────────────────────────────────

    /** 默认数据 subId；自 API 24 起是公开静态方法。 */
    private fun defaultDataSubId(): Int = try {
        exemptionApplied
        staticInvoke(
            SUBSCRIPTION_MANAGER,
            "getDefaultDataSubscriptionId",
            parameterTypes = emptyList(),
            arguments = emptyList()
        ) as? Int ?: UNAVAILABLE
    } catch (failure: Throwable) {
        Log.e(TAG, "getDefaultDataSubscriptionId 调用失败", failure)
        UNAVAILABLE
    }

    /**
     * 当前默认数据 SIM 的逻辑卡槽（0/1），失败返回 -1。
     *
     * `getSlotIndex(int)` 自 API 29 起可用，且与 `getDefaultDataSubscriptionId()` 一样都是公开
     * 静态方法，因此没有 Context 的 `app_process` root 进程也能直接调用。
     */
    fun getDefaultDataSlot(): Int {
        val subId = defaultDataSubId()
        if (subId < 0) {
            Log.w(TAG, "getDefaultDataSlot: 拿不到默认数据 subId")
            return UNAVAILABLE
        }
        return try {
            val slot = staticInvoke(
                SUBSCRIPTION_MANAGER,
                "getSlotIndex",
                parameterTypes = listOf(Int::class.javaPrimitiveType!!),
                arguments = listOf(subId)
            ) as? Int ?: UNAVAILABLE
            if (slot < 0) {
                Log.w(TAG, "getDefaultDataSlot: getSlotIndex($subId) -> $slot")
                UNAVAILABLE
            } else {
                slot
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "getDefaultDataSlot 失败", failure)
            UNAVAILABLE
        }
    }

    /**
     * 只读探针：本进程现在能不能用反射。
     *
     * 返回 null 表示可用，否则返回一句可以直接摆到界面上的原因。依次尝试 `ITelephony` 句柄、
     * `getAllowedNetworkTypesForReason`、`getPreferredNetworkType`，每一步失败都转成文字而不是
     * 抛出，让通道能把原因原样透出。
     */
    fun probe(): String? {
        val stub = when (val binding = bindTelephony()) {
            is Binding.Ready -> binding.stub
            is Binding.Broken -> return binding.reason
        }
        val subId = defaultDataSubId().coerceAtLeast(0)

        if (dispatch(stub, "getAllowedNetworkTypesForReason", allowedTypeReads(subId)) is CallResult.Hit) {
            return null
        }
        if (dispatch(stub, "getPreferredNetworkType", preferredTypeReads(subId)) is CallResult.Hit) {
            return null
        }
        return "ITelephony 上没有可用的只读重载（getAllowedNetworkTypesForReason / getPreferredNetworkType 都没调通），subId=$subId"
    }

    /** 当前 RIL 网络模式，取不到返回 -1。 */
    fun getCurrentNetworkMode(subId: Int, caller: String): Int {
        val stub = telephonyStub(caller) ?: return UNAVAILABLE
        return try {
            // Android 11+ 只给「允许的网络类型」位掩码，需要反查成 RIL 模式。
            val maskResult = dispatch(stub, "getAllowedNetworkTypesForReason", allowedTypeReads(subId) { it as Long })
            if (maskResult is CallResult.Hit) {
                val networkTypes = maskResult.value as Long
                val mode = bitmaskMapper.toNetworkMode(networkTypes)
                Log.i(TAG, "$caller 读到位掩码 subId=$subId -> $mode (networkTypes=$networkTypes)")
                return mode
            }

            // Android 10 及更早直接返回 RIL 模式。
            val legacyResult = dispatch(stub, "getPreferredNetworkType", preferredTypeReads(subId) { it as Int })
            if (legacyResult is CallResult.Hit) {
                val mode = legacyResult.value as Int
                Log.i(TAG, "$caller 读到旧接口模式 subId=$subId -> $mode")
                return mode
            }
            UNAVAILABLE
        } catch (failure: Throwable) {
            Log.e(TAG, "$caller 读取网络模式时抛异常", failure)
            UNAVAILABLE
        }
    }

    /**
     * 如实报告本机 `ITelephony` 上「写入」相关方法各存在几个重载。
     *
     * 为什么要设备自己说：[setNetworkMode] 里那「三条策略」是按 AOSP 的历史版本写出来的，
     * 而 Android 14 起 `setAllowedNetworkTypes(long)` 与 `setPreferredNetworkType(int)` 在
     * `ITelephony` 上已经不存在了。这件事以前只能靠读 AOSP 源码推断，现在由运行中的设备报告。
     * 只做方法枚举，不需要任何权限（拿 binder 也不需要），因此应用进程也能调。
     */
    fun describeWriteMethods(): String {
        val stub = when (val binding = bindTelephony()) {
            is Binding.Ready -> binding.stub
            is Binding.Broken -> return "ITelephony 不可达（" + binding.reason + "）"
        }
        return WRITE_METHODS.joinToString("；") { name ->
            val overloads = stub.javaClass.methods.filter { it.name == name }
            if (overloads.isEmpty()) {
                name + " 不存在"
            } else {
                name + " " + overloads.size + " 个重载（" +
                    overloads.joinToString(" / ") { describeTypes(it.parameterTypes) } + "）"
            }
        }
    }

    /**
     * 写入 [networkMode]，返回三条策略里是否有任意一条成功。
     *
     * 三条策略按「新接口 -> 旧接口 -> 更旧接口」的顺序试，先成功即返回。
     *
     * 诊断口径（重要）：`setAllowedNetworkTypesForReason` 的返回值**就是调制解调器有没有
     * 接受这个模式**，本方法只把它记进日志，**不参与返回值判定**。改成「modem 说 false 就算
     * 失败」会改变降级/恢复的判定语义（外层会因此回落到 settings 写入或把这次切换标记为
     * 失败），属于行为变更，必须先由用户确认。所以这里先把事实记全，让「到底卡在哪一步」可查。
     */
    fun setNetworkMode(subId: Int, networkMode: Int, caller: String): Boolean {
        val stub = telephonyStub(caller)
        if (stub == null) {
            WriteDiag.warn("$caller 没有可用的 ITelephony 通道，写入未执行 subId=$subId mode=$networkMode")
            return false
        }
        return try {
            val networkTypes = bitmaskMapper.toBitmask(networkMode)
            if (networkTypes == null) {
                // 1.5.0：表外模式以前会被当成 ALL_NETWORK_TYPES 写下去 —— 一次「锁 5G」会变成
                // 「不限制任何制式」。现在直接拒绝，并把「为什么没写」记进日志页。
                WriteDiag.warn(
                    "$caller 模式 $networkMode 不在本机位掩码表内（表内 0..${NetworkModeBitmaskMapper.MAX_NETWORK_MODE}），" +
                        "已拒绝写入：继续写会把这张卡放开到全部制式"
                )
                return false
            }
            WriteDiag.detail("$caller 目标 subId=$subId mode=$networkMode -> 位掩码=$networkTypes")
            // 详细日志：先把「本机 ITelephony 上到底有哪几条写入方法」摊开。定制 ROM 上最常见的
            // 失败形态就是方法被删或被改写，这一行是判断「该不该走这条路」的第一手依据。
            WriteDiag.detail("$caller 本机 ITelephony 写入方法枚举：" + describeWriteMethods())
            // 逐候选的追踪只在详细模式开启时才构造；常态下 trace 为 null，零额外开销。
            val trace: ((String) -> Unit)? = if (WriteDiag.isVerbose) { { WriteDiag.detail(it) } } else null
            // 1.5.1 的分工：**过程**（哪一条策略怎么失败、被谁拒、原文是什么）归详细开关管；
            // **结论**（这次写入成没成、为什么没成）必须无条件看得见。所以每条被拒的原文先收集起来，
            // 由下面那条最终的 warn 一次性带出去；verbose 打开时再逐条实时打一份。
            val failures = mutableListOf<String>()
            val onDenied: (String) -> Unit = {
                failures += it
                // 被拒的**原文**属于「失败时系统返回了什么」，两种模式都要记：以前这行是 detail，
                // 简要模式下就只剩一句「全部失败」，看不到是谁拒的、原话是什么。
                WriteDiag.failure("$caller ITelephony 写入被拒：$it")
            }

            // 逐策略记「返回了什么类型 + 花了多久」：方法不存在 / 调用返回 false / 抛异常
            // 在简要模式里都长成「全 Miss」，只有这一行能把它们分开。
            val strategyStart = System.currentTimeMillis()
            val reasonWrite = dispatch(stub, "setAllowedNetworkTypesForReason", allowedTypeWrites(subId, networkTypes), trace, onDenied)
            WriteDiag.detail(
                "$caller 策略1 setAllowedNetworkTypesForReason -> $reasonWrite" +
                    "（耗时 ${System.currentTimeMillis() - strategyStart}ms）"
            )
            if (reasonWrite is CallResult.Hit) {
                Log.i(TAG, "$caller 走 setAllowedNetworkTypesForReason 写入成功 subId=$subId mode=$networkMode")
                WriteDiag.always(
                    "$caller 策略1 setAllowedNetworkTypesForReason(subId=$subId, REASON_USER, $networkTypes) " +
                        "已调用，modem 返回 ${reasonWrite.value}"
                )
                return true
            }

            val strategyStart2 = System.currentTimeMillis()
            val legacyWrite = dispatch(stub, "setAllowedNetworkTypes", legacyAllowedTypeWrites(subId, networkTypes), trace, onDenied)
            WriteDiag.detail(
                "$caller 策略2 setAllowedNetworkTypes -> $legacyWrite" +
                    "（耗时 ${System.currentTimeMillis() - strategyStart2}ms）"
            )
            if (legacyWrite is CallResult.Hit) {
                Log.i(TAG, "$caller 走 setAllowedNetworkTypes 写入成功 subId=$subId networkTypes=$networkTypes")
                WriteDiag.always("$caller 策略2 setAllowedNetworkTypes(subId=$subId, $networkTypes) 已调用，返回 ${legacyWrite.value}")
                return true
            }

            val strategyStart3 = System.currentTimeMillis()
            val modeWrite = dispatch(stub, "setPreferredNetworkType", preferredTypeWrites(subId, networkMode), trace, onDenied)
            WriteDiag.detail(
                "$caller 策略3 setPreferredNetworkType -> $modeWrite" +
                    "（耗时 ${System.currentTimeMillis() - strategyStart3}ms）"
            )
            if (modeWrite is CallResult.Hit) {
                Log.i(TAG, "$caller 走 setPreferredNetworkType 写入成功 subId=$subId mode=$networkMode")
                WriteDiag.always("$caller 策略3 setPreferredNetworkType(subId=$subId, $networkMode) 已调用，返回 ${modeWrite.value}")
                return true
            }

            Log.w(TAG, "$caller 三条写入策略全部失败 subId=$subId mode=$networkMode")
            // 1.5.0：把「本机实际有几条可用」一起报出来。AOSP 14 起 ITelephony 上只剩
            // setAllowedNetworkTypesForReason，另两条已不存在 —— 也就是说这里的「三条策略」
            // 在多数新机上是**一条**，不写清楚会被误读成「三条都试过了所以没辙」。
            WriteDiag.warn(
                "$caller ITelephony 写入策略全部失败 subId=$subId mode=$networkMode bitmask=$networkTypes；本机实际可用：" +
                    describeWriteMethods() +
                    "；逐策略问题：" +
                    failures.joinToString("；")
                        .ifEmpty { "三条策略都调用到了，但没有一条返回成功（AOSP 14+ 多数机型只剩策略1）" }
            )
            false
        } catch (failure: Throwable) {
            Log.e(TAG, "$caller 写入网络模式时抛异常", failure)
            WriteDiag.warn("$caller 写入网络模式抛异常：${describe(failure)}")
            false
        }
    }
}
