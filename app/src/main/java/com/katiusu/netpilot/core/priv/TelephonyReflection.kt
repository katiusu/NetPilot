package com.katiusu.netpilot.core.priv

import android.content.Context
import android.os.Build
import android.os.IBinder
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
    private fun dispatch(receiver: Any, target: String, candidates: List<Candidate>): CallResult {
        for (method in receiver.javaClass.methods) {
            if (method.name != target) continue
            for (candidate in candidates) {
                if (!candidate.fits(method.parameterTypes)) continue
                try {
                    return CallResult.Hit(candidate.fire(receiver, method))
                } catch (_: Exception) {
                    // 该组合在本机不可用，继续试下一个
                }
            }
        }
        return CallResult.Miss
    }

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
     * 写入 [networkMode]，返回三条策略里是否有任意一条成功。
     *
     * 三条策略按「新接口 -> 旧接口 -> 更旧接口」的顺序试，先成功即返回，不校验调制解调器是否
     * 真的接受了这个模式。
     */
    fun setNetworkMode(subId: Int, networkMode: Int, caller: String): Boolean {
        val stub = telephonyStub(caller) ?: return false
        return try {
            val networkTypes = bitmaskMapper.toBitmask(networkMode)

            val reasonWrite = dispatch(stub, "setAllowedNetworkTypesForReason", allowedTypeWrites(subId, networkTypes))
            if (reasonWrite is CallResult.Hit) {
                Log.i(TAG, "$caller 走 setAllowedNetworkTypesForReason 写入成功 subId=$subId mode=$networkMode")
                return true
            }

            val legacyWrite = dispatch(stub, "setAllowedNetworkTypes", legacyAllowedTypeWrites(subId, networkTypes))
            if (legacyWrite is CallResult.Hit) {
                Log.i(TAG, "$caller 走 setAllowedNetworkTypes 写入成功 subId=$subId networkTypes=$networkTypes")
                return true
            }

            val modeWrite = dispatch(stub, "setPreferredNetworkType", preferredTypeWrites(subId, networkMode))
            if (modeWrite is CallResult.Hit) {
                Log.i(TAG, "$caller 走 setPreferredNetworkType 写入成功 subId=$subId mode=$networkMode")
                return true
            }

            Log.w(TAG, "$caller 三条写入策略全部失败 subId=$subId mode=$networkMode")
            false
        } catch (failure: Throwable) {
            Log.e(TAG, "$caller 写入网络模式时抛异常", failure)
            false
        }
    }
}
