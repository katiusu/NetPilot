package com.katiusu.netpilot.core.priv.shizuku

import android.content.Context
import androidx.annotation.Keep
import com.katiusu.netpilot.core.mode.NetworkModeBitmaskMapper
import com.katiusu.netpilot.core.priv.AuthStore
import com.katiusu.netpilot.core.priv.SubscriptionSwitcher
import com.katiusu.netpilot.core.priv.TelephonyReflection
import com.katiusu.netpilot.core.priv.WriteDiag

/**
 * 跑在 Shizuku 用户进程里的 binder 服务（uid = shell 或 root），只有在这里才持有
 * MODIFY_PHONE_STATE。逻辑全部转调 [TelephonyReflection] / [SubscriptionSwitcher]。
 *
 * 用户服务**不需要**在 AndroidManifest.xml 里注册：Shizuku 会通过 app_process
 * 反射实例化本类（见 Shizuku API 文档 `Shizuku.UserService`）。
 */
class ShizukuControllerService() : IShizukuController.Stub() {

    /** 用户服务进程里的包名，Shizuku 用带 Context 的构造器实例化本类时填上。 */
    @Volatile
    private var appPackage: String? = null

    /**
     * 用户服务进程的 Context。
     *
     * 为什么到 1.5.0 才留：这一列（`siminfo.allowed_network_types`）的读写要靠 ContentResolver，
     * 而 ContentResolver 必须从 Context 取。**不能**改用应用进程的 resolver —— 那道门要特权身份，
     * 而只有本进程（uid=shell/root）是特权身份。
     */
    @Volatile
    private var appContext: Context? = null

    /** Shizuku instantiates the service through this constructor. */
    @Keep
    constructor(context: Context) : this() {
        // 用户服务进程里有 Context：活动卡列表可以走公开 API（需要 READ_PHONE_STATE）
        SubscriptionSwitcher.attachContext(context)
        appPackage = context.packageName
        appContext = context.applicationContext ?: context
    }

    override fun probe(): Boolean = TelephonyReflection.probe() == null

    override fun getCurrentNetworkMode(subId: Int): Int =
        TelephonyReflection.getCurrentNetworkMode(subId, CALLER)

    override fun setNetworkMode(subId: Int, networkMode: Int): Boolean {
        if (TelephonyReflection.setNetworkMode(subId, networkMode, CALLER)) return true
        // 1.5.0：ITelephony 走不通时先写**权威存储**，而不是直接认输。
        // 为什么必须在本进程写：这一列要特权身份，应用进程写不了（见 appContext 的注释）。
        val networkTypes = NetworkModeBitmaskMapper.platform.toBitmask(networkMode) ?: run {
            WriteDiag.warn(
                "$CALLER 模式 $networkMode 不在本机位掩码表内（表内 0..${NetworkModeBitmaskMapper.MAX_NETWORK_MODE}），" +
                    "跳过权威存储写入"
            )
            return false
        }
        WriteDiag.detail(
            "$CALLER 三条 ITelephony 策略都没成，转写权威存储：subId=$subId mode=$networkMode -> 位掩码=$networkTypes"
        )
        val result = AuthStore.decodeWrite(writeAuthStore(subId, networkTypes))
        // 1.5.1：这一行是整个过程里的一步，归详细诊断开关管；结论（切换成功/失败 + 原因）
        // 由应用进程侧的 ShizukuController.setMode / NetPilot.setMode 写进日志页。
        WriteDiag.detail("$CALLER ITelephony 失败后写权威存储：${AuthStore.describeWrite(result)}")
        return result is AuthStore.Write.Ok
    }

    override fun readAuthStore(subId: Int): String {
        val resolver = appContext?.contentResolver
        // 详细日志：Context 有没有就绪，是这一层最常见的失败原因，而它在应用进程侧看不出来。
        WriteDiag.detail(
            "$CALLER 读权威存储：subId=$subId ContentResolver=${if (resolver == null) "无（Context 未就绪）" else "有"}"
        )
        return AuthStore.encodeRead(AuthStore.read(resolver, subId))
    }

    override fun writeAuthStore(subId: Int, networkTypes: Long): String {
        val resolver = appContext?.contentResolver
        val updated = AuthStore.write(resolver, subId, networkTypes)
        if (updated !is AuthStore.Write.Ok) {
            WriteDiag.detail("$CALLER 权威存储写入未成功：${AuthStore.describeWrite(updated)}")
            return AuthStore.encodeWrite(updated)
        }
        // 写后回读：`resolver.update` 返回行数只说明 provider 收下了，不代表那一列变成了目标值。
        return AuthStore.encodeWrite(
            when (val back = AuthStore.read(resolver, subId)) {
                is AuthStore.Read.Value ->
                    if (back.networkTypes == networkTypes) AuthStore.Write.Ok
                    else AuthStore.Write.Mismatch(back.networkTypes, networkTypes)
                else -> AuthStore.Write.NotVerified(AuthStore.describeRead(back))
            }
        )
    }

    override fun getDefaultSlot(): Int = TelephonyReflection.getDefaultDataSlot()

    override fun setDefaultSlot(slot: Int): Boolean {
        val subId = SubscriptionSwitcher.resolveSubIdBySlot(slot)
        return subId >= 0 && SubscriptionSwitcher.setDefaultDataSubId(subId)
    }

    override fun activeSlots(): IntArray =
        SubscriptionSwitcher.activeSlotList().flatMap { listOf(it.first, it.second) }.toIntArray()

    /** 回收残留的用户服务孤儿进程，见 [StaleProcessPruner]。返回杀掉的个数，失败 0。 */
    override fun pruneStaleProcesses(): Int = runCatching {
        StaleProcessPruner.prune(appPackage ?: FALLBACK_PACKAGE)
    }.getOrDefault(0)

    override fun destroy() {
        // 用户服务的生命周期由 Shizuku 管理，这里只解除 Context 引用，避免泄漏 Activity
        SubscriptionSwitcher.attachContext(null)
        appContext = null
    }

    private companion object {
        const val CALLER = "shizuku"

        /** 兜底包名：Shizuku 理论上总会用带 Context 的构造器，真拿不到时用它。 */
        const val FALLBACK_PACKAGE = "com.katiusu.netpilot"
    }
}
