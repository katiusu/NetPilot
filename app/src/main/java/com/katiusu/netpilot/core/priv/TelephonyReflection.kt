package com.katiusu.netpilot.core.priv

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.katiusu.netpilot.core.mode.NetworkModeBitmaskMapper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * Reads and writes the preferred network mode through `ITelephony`.
 *
 * `android.os.ServiceManager` and `com.android.internal.telephony.ITelephony` are
 * non-SDK APIs whose signatures drift between Android versions and OEM builds. Access
 * is reflective throughout so that a missing or changed method degrades to a logged
 * failure rather than a `NoSuchMethodError`.
 *
 * Must run in a privileged process — the root service (app_process) or the Shizuku
 * user service. The setters require MODIFY_PHONE_STATE, which the app process does not
 * hold. Ported from NetworkSwitch (MIT) with the caller/package renamed to NetPilot.
 */
object TelephonyReflection {

    private const val TAG = "NetPilot"
    private const val PHONE_PACKAGE = "com.android.phone"

    private val mapper get() = NetworkModeBitmaskMapper.platform
    private val reasonUser get() = NetworkModeBitmaskMapper.reasonUser

    /**
     * Exempts the telephony packages from non-SDK interface enforcement, once.
     *
     * `Landroid/telephony/` additionally covers `android.telephony.SubscriptionManager`
     * (used by [getDefaultDataSlot] and SubscriptionSwitcher), and
     * `Landroid/os/ServiceManager` covers the hidden `ServiceManager.getService` that
     * resolves the telephony binder.
     *
     * Redundant in the Shizuku user service, which is documented as already exempt;
     * the `app_process` root entry point gives no such guarantee.
     * `addHiddenApiExemptions` appends, where `setHiddenApiExemptions` would clear
     * another library's entries.
     */
    private val hiddenApiExempted: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@lazy true
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/telephony/",
                "Lcom/android/internal/telephony/",
                "Landroid/os/ServiceManager"
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Hidden API exemption failed; continuing since the process may already be exempt", e)
            false
        }
    }

    /**
     * Reuses the one-time hidden-API exemption from [SubscriptionSwitcher], which
     * reflects into the same packages from a different entry point.
     */
    fun ensureHiddenApiExempted(): Boolean = hiddenApiExempted

    private fun getITelephony(caller: String): Any? = try {
        hiddenApiExempted // read to trigger the one-time exemption
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.TELEPHONY_SERVICE) as? IBinder
        Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    } catch (e: Exception) {
        Log.e(TAG, "$caller: Failed to get ITelephony", e)
        null
    }

    /**
     * Read-only probe: is reflection usable here?
     *
     * Returns `null` when it is, otherwise a human-readable reason. Tries, in order,
     * the `ITelephony` handle, `getAllowedNetworkTypesForReason` and
     * `getPreferredNetworkType`; every failure is reported instead of thrown so that a
     * channel can surface the reason verbatim in the UI.
     */
    fun probe(): String? {
        val iTelephony: Any = try {
            hiddenApiExempted
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, Context.TELEPHONY_SERVICE) as? IBinder
                ?: return "ServiceManager.getService(telephony) 返回 null（telephony 服务不可达）"
            Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                ?: return "ITelephony.Stub.asInterface 返回 null"
        } catch (e: Throwable) {
            return "获取 ITelephony 失败: " + e.javaClass.simpleName + ": " + e.message
        }

        val methods = iTelephony.javaClass.methods
        val subId = defaultDataSubId().let { if (it >= 0) it else 0 }

        for (m in methods.named("getAllowedNetworkTypesForReason")) {
            try {
                when {
                    m.parameterCount == 2 -> {
                        m.invoke(iTelephony, subId, reasonUser)
                        return null
                    }
                    m.parameterCount == 3 && m.parameterTypes[2] == String::class.java -> {
                        m.invoke(iTelephony, subId, reasonUser, PHONE_PACKAGE)
                        return null
                    }
                    else -> continue
                }
            } catch (_: Exception) {
                // Signature did not match after all; try the next overload.
            }
        }

        for (m in methods.named("getPreferredNetworkType")) {
            try {
                when {
                    m.parameterCount == 1 -> {
                        m.invoke(iTelephony, subId)
                        return null
                    }
                    m.parameterCount == 2 && m.parameterTypes[1] == String::class.java -> {
                        m.invoke(iTelephony, subId, PHONE_PACKAGE)
                        return null
                    }
                    else -> continue
                }
            } catch (_: Exception) {
                // Try the next overload.
            }
        }

        return "ITelephony 无可用只读重载（getAllowedNetworkTypesForReason / getPreferredNetworkType 全部调用失败），subId=$subId"
    }

    /**
     * The logical slot (0/1) of the current default data SIM, or -1 on failure.
     *
     * `SubscriptionManager.getDefaultDataSubscriptionId()` is a public static since
     * API 24 and `getSlotIndex(int)` since API 29; both are called statically so this
     * works in a process without a Context (the `app_process` root entry point).
     */
    fun getDefaultDataSlot(): Int = try {
        val subId = defaultDataSubId()
        if (subId < 0) {
            Log.w(TAG, "getDefaultDataSlot: no default data subId")
            -1
        } else {
            val slot = Class.forName("android.telephony.SubscriptionManager")
                .getMethod("getSlotIndex", Int::class.javaPrimitiveType)
                .invoke(null, subId) as? Int ?: -1
            if (slot < 0) {
                Log.w(TAG, "getDefaultDataSlot: getSlotIndex($subId) -> $slot")
                -1
            } else {
                slot
            }
        }
    } catch (e: Throwable) {
        Log.e(TAG, "getDefaultDataSlot failed", e)
        -1
    }

    /** Default data subId, or -1. public static since API 24. */
    private fun defaultDataSubId(): Int = try {
        hiddenApiExempted
        Class.forName("android.telephony.SubscriptionManager")
            .getMethod("getDefaultDataSubscriptionId")
            .invoke(null) as? Int ?: -1
    } catch (e: Throwable) {
        Log.e(TAG, "getDefaultDataSubscriptionId failed", e)
        -1
    }

    /** Returns the current RIL network mode, or -1 if it could not be determined. */
    fun getCurrentNetworkMode(subId: Int, caller: String): Int {
        try {
            val iTelephony = getITelephony(caller) ?: return -1
            val methods = iTelephony.javaClass.methods

            // Android 11+ reports an allowed-network-types bitmask.
            for (m in methods.named("getAllowedNetworkTypesForReason")) {
                try {
                    val bitmask = when {
                        m.parameterCount == 2 ->
                            m.invoke(iTelephony, subId, reasonUser) as Long
                        m.parameterCount == 3 && m.parameterTypes[2] == String::class.java ->
                            m.invoke(iTelephony, subId, reasonUser, PHONE_PACKAGE) as Long
                        else -> continue
                    }
                    val mode = mapper.toNetworkMode(bitmask)
                    Log.i(TAG, "$caller: getAllowedNetworkTypesForReason(subId=$subId) -> $mode (bitmask=$bitmask)")
                    return mode
                } catch (_: Exception) {
                    // Signature did not match after all; try the next overload.
                }
            }

            // Android 10 and below expose the RIL mode directly.
            for (m in methods.named("getPreferredNetworkType")) {
                try {
                    val mode = when {
                        m.parameterCount == 1 -> m.invoke(iTelephony, subId) as Int
                        m.parameterCount == 2 && m.parameterTypes[1] == String::class.java ->
                            m.invoke(iTelephony, subId, PHONE_PACKAGE) as Int
                        else -> continue
                    }
                    Log.i(TAG, "$caller: getPreferredNetworkType(subId=$subId) -> $mode")
                    return mode
                } catch (_: Exception) {
                    // Try the next overload.
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "$caller: Error in getCurrentNetworkMode", e)
        }
        return -1
    }

    /** Applies [networkMode], returning whether any of the three strategies took. */
    fun setNetworkMode(subId: Int, networkMode: Int, caller: String): Boolean {
        try {
            val iTelephony = getITelephony(caller) ?: return false
            val bitmask = mapper.toBitmask(networkMode)
            val methods = iTelephony.javaClass.methods

            for (m in methods.named("setAllowedNetworkTypesForReason")) {
                try {
                    when {
                        m.parameterCount == 3 ->
                            m.invoke(iTelephony, subId, reasonUser, bitmask)
                        m.parameterCount == 4 && m.parameterTypes[3] == String::class.java ->
                            m.invoke(iTelephony, subId, reasonUser, bitmask, PHONE_PACKAGE)
                        else -> continue
                    }
                    Log.i(TAG, "$caller: setAllowedNetworkTypesForReason(subId=$subId, mode=$networkMode) success")
                    return true
                } catch (_: Exception) {
                    // Try the next overload.
                }
            }

            for (m in methods.named("setAllowedNetworkTypes")) {
                try {
                    when {
                        m.parameterCount == 2 -> m.invoke(iTelephony, subId, bitmask)
                        m.parameterCount == 3 && m.parameterTypes[1].name == "long" ->
                            m.invoke(iTelephony, subId, bitmask, PHONE_PACKAGE)
                        else -> continue
                    }
                    Log.i(TAG, "$caller: setAllowedNetworkTypes(subId=$subId, bitmask=$bitmask) success")
                    return true
                } catch (_: Exception) {
                    // Try the next overload.
                }
            }

            for (m in methods.named("setPreferredNetworkType")) {
                if (m.parameterCount != 2) continue
                try {
                    m.invoke(iTelephony, subId, networkMode)
                    Log.i(TAG, "$caller: setPreferredNetworkType(subId=$subId, mode=$networkMode) success")
                    return true
                } catch (_: Exception) {
                    // Try the next overload.
                }
            }

            Log.w(TAG, "$caller: Failed to set network mode $networkMode for subId $subId")
        } catch (e: Throwable) {
            Log.e(TAG, "$caller: FATAL error in setNetworkMode", e)
        }
        return false
    }

    private fun Array<Method>.named(name: String) = filter { it.name == name }
}
