package com.katiusu.netpilot.core.priv

import com.katiusu.netpilot.core.mode.NetworkMode

/** 实际生效的特权通道。 */
enum class ControlMethod { ROOT, SHIZUKU, NONE }

/**
 * 通道可用性。三种状态刻意分开：
 * - [Available] 可直接用；
 * - [PermissionDenied] 通道本身在（Shizuku 在跑 / su 在），但没给本应用授权，UI 应引导授权；
 * - [Unavailable] 通道根本不具备（Shizuku 未运行、设备未 root）。
 */
sealed interface ChannelStatus {
    data object Available : ChannelStatus
    data class PermissionDenied(val method: ControlMethod, val hint: String) : ChannelStatus
    data class Unavailable(val method: ControlMethod, val reason: String) : ChannelStatus
}

/**
 * 特权网络控制通道。实现必须是线程安全且可重复调用的：
 * 失败返回 -1 / false / 空列表，绝不抛异常到调用方（UI 只做兜底渲染）。
 */
interface NetworkControlChannel {

    /** 通道短名（"Root" / "Shizuku"），直接用于 UI 与日志。 */
    val label: String

    val method: ControlMethod

    fun isConnected(): Boolean

    suspend fun probe(): ChannelStatus

    /** -1 失败 */
    suspend fun getMode(subId: Int): Int

    suspend fun setMode(subId: Int, mode: NetworkMode): Boolean

    /** -1 失败 */
    suspend fun getDefaultSlot(): Int

    suspend fun setDefaultSlot(slot: Int): Boolean

    /** (slot, subId) 列表 */
    suspend fun activeSlots(): List<Pair<Int, Int>>

    /**
     * 读一次**权威存储**（Android 11+ 是 TelephonyProvider 的 `siminfo.allowed_network_types`，
     * 不是 `Settings.Global.preferred_network_mode`）。见 [AuthStore]。
     *
     * 给默认实现而不是抽象方法：这个接口有很多只读用途，没实现它的通道应当如实回答「未实现」，
     * 而不是因为少一个方法编译不过 —— 更不该假装读到了一个值。
     */
    suspend fun readAuthStore(subId: Int): AuthStore.Read =
        AuthStore.Read.Unavailable("通道 $label 未实现权威存储读取")

    /** 写权威存储；默认未实现，同样返回事实而不是抛异常。 */
    suspend fun writeAuthStore(subId: Int, networkTypes: Long): AuthStore.Write =
        AuthStore.Write.Failed("通道 $label 未实现权威存储写入")

    fun destroy()
}
