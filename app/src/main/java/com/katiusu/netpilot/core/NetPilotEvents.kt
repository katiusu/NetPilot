package com.katiusu.netpilot.core

import android.content.Context
import com.katiusu.netpilot.core.monitor.SignalSnapshot

/**
 * 监控循环 → 业务模块的单向事件出口。
 *
 * 存在的理由：监控引擎（core.monitor）不应该 import 数据卡规则（core.datacard），
 * 否则两个包互相依赖，单元测试也没法单独替换。这里用一个极小的函数钩子把方向
 * 固定成 monitor → hub ← datacard，接线在 [NetPilot.install] 一处完成。
 */
object NetPilotEvents {

    /** 每次采样完成后回调；默认空实现（没有模块订阅时也安全）。 */
    @Volatile
    var onSample: (suspend (Context, SignalSnapshot) -> String?)? = null

    /** 监控起停回调，给磁贴/界面刷新用。 */
    @Volatile
    var onMonitorStateChanged: ((Boolean) -> Unit)? = null

    /**
     * 每卡「网络质量自动降级」门控：`(subId) -> 这张卡是否允许被自动降级`。
     *
     * 这是 [onSample] 的反方向调用。网络质量降级的执行者是 monitor 侧的
     * [com.katiusu.netpilot.core.monitor.AutoDowngradeEngine]（全局单实例、只作用于
     * 当前默认数据卡），而「哪张卡开没开这条策略」的知识在 datacard 侧。monitor 不
     * 能反向 import datacard（两个包会成环），所以同样收敛到这个 hub，装配点仍然是
     * [NetPilot.install] 一处。
     */
    @Volatile
    var qualityDowngradeAllowed: ((Int) -> Boolean)? = null

    /**
     * 查询某张卡是否允许被自动降级。
     *
     * 没接线时返回 `true`：数据卡模块缺席、或策略尚未读出来时，不应该把整条降级
     * 功能锁死 —— 否则用户会看到「功能明明开着，却永远不降级」这种查不出原因的现象。
     */
    fun allowsQualityDowngrade(subId: Int): Boolean =
        qualityDowngradeAllowed?.invoke(subId) ?: true
}
