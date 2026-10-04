package com.katiusu.netpilot.core

import android.content.Context
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.prefs.ConfigState

/**
 * 后台服务总开关（网络监控前台服务 / 保活心跳 / 被杀重启 / 开机自启的统一闸门）。
 *
 * 为什么本类刻意**不** import MonitorService 与 KeepAliveScheduler：
 * MonitorService 在 `onDestroy` / `onTaskRemoved` 里要反过来问「总开关还开着吗」，
 * 如果这里再持有服务与调度器，就会形成
 * `ServicesGate -> MonitorService -> ServicesGate` 的循环依赖。
 * 因此这里只做「一个配置键的读写」，是依赖图里的叶子节点；
 * 「关掉开关」的副作用（停前台服务、撤闹钟）统一收口在 [NetPilot.setServicesEnabled]。
 */
object ServicesGate {

    /** 存储键。默认 **true**：老用户升级上来不该突然丢后台能力。 */
    const val KEY_SERVICES_ENABLED = "np_services_enabled"
    const val DEFAULT_ENABLED = true

    private const val TAG = "后台服务开关"

    /**
     * 总开关当前值。
     *
     * 读之前先幂等 [ConfigState.init]：`ConfigState` 内部是内存状态表，没 init 过时它是空的，
     * 直接读会静默回退到默认值 true —— 那会让「用户明明关了总开关」在冷启动进程
     * （保活心跳广播、开机广播）里失效，服务又被拉起来。这是必须显式 init 的唯一原因。
     */
    fun enabled(context: Context): Boolean {
        ensureLoaded(context)
        return runCatching { ConfigState.bool(KEY_SERVICES_ENABLED, DEFAULT_ENABLED) }
            .getOrDefault(DEFAULT_ENABLED)
    }

    /**
     * 只落盘 + 记日志，不碰任何服务与闹钟。
     * 需要「关掉开关就真的全部停下」请调用 [NetPilot.setServicesEnabled]。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        ConfigState.set(KEY_SERVICES_ENABLED, enabled)
        LogStore.info(
            TAG,
            if (enabled) {
                "总开关已开启：网络监控、保活心跳、被杀重启、开机自启重新生效"
            } else {
                "总开关已关闭：网络监控、保活心跳、被杀重启、开机自启全部停止"
            },
        )
    }

    private fun ensureLoaded(context: Context) {
        runCatching { ConfigState.init(context.applicationContext) }
    }
}
