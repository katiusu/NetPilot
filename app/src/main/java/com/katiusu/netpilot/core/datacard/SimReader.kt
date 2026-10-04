package com.katiusu.netpilot.core.datacard

import android.content.Context
import android.telephony.SubscriptionManager
import com.katiusu.netpilot.core.monitor.LogStore

/**
 * 读取当前双卡信息。
 *
 * 全部包 runCatching：没有 READ_PHONE_STATE / READ_PHONE_NUMBERS 时
 * `getActiveSubscriptionInfoList` 会抛 SecurityException，这里的策略是
 * 「读不到就当没有卡」，绝不让界面因为权限缺失而崩。
 */
object SimReader {

    private const val TAG = "双卡读取"

    fun sims(context: Context): List<SimSlotInfo> = runCatching {
        val sm = context.getSystemService(SubscriptionManager::class.java)
            ?: return emptyList()
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        @Suppress("MissingPermission")
        val list = sm.activeSubscriptionInfoList.orEmpty()
        list.map { info ->
            SimSlotInfo(
                slotIndex = info.simSlotIndex,
                subId = info.subscriptionId,
                carrierName = info.carrierName?.toString().orEmpty(),
                displayName = info.displayName?.toString().orEmpty(),
                isDefaultData = info.subscriptionId == defaultData,
                isActive = true,
            )
        }.sortedBy { it.slotIndex }
    }.getOrElse { t ->
        LogStore.debug(TAG, "读取 SIM 列表失败：${t.message ?: t.javaClass.simpleName}")
        emptyList()
    }

    fun defaultDataSubId(): Int = runCatching {
        SubscriptionManager.getDefaultDataSubscriptionId()
    }.getOrDefault(-1)

    fun subIdOfSlot(context: Context, slotIndex: Int): Int =
        sims(context).firstOrNull { it.slotIndex == slotIndex }?.subId ?: -1
}
