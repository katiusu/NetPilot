package com.katiusu.netpilot.core.priv.shizuku

import android.content.Context
import androidx.annotation.Keep
import com.katiusu.netpilot.core.priv.SubscriptionSwitcher
import com.katiusu.netpilot.core.priv.TelephonyReflection

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

    /** Shizuku instantiates the service through this constructor. */
    @Keep
    constructor(context: Context) : this() {
        // 用户服务进程里有 Context：活动卡列表可以走公开 API（需要 READ_PHONE_STATE）
        SubscriptionSwitcher.attachContext(context)
        appPackage = context.packageName
    }

    override fun probe(): Boolean = TelephonyReflection.probe() == null

    override fun getCurrentNetworkMode(subId: Int): Int =
        TelephonyReflection.getCurrentNetworkMode(subId, CALLER)

    override fun setNetworkMode(subId: Int, networkMode: Int): Boolean =
        TelephonyReflection.setNetworkMode(subId, networkMode, CALLER)

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
    }

    private companion object {
        const val CALLER = "shizuku"

        /** 兜底包名：Shizuku 理论上总会用带 Context 的构造器，真拿不到时用它。 */
        const val FALLBACK_PACKAGE = "com.katiusu.netpilot"
    }
}
