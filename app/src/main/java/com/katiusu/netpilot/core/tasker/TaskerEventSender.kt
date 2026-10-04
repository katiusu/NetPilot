package com.katiusu.netpilot.core.tasker

import android.content.Context
import android.content.Intent
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.core.monitor.SignalSnapshot

/**
 * NetPilot → Tasker 的事件发送出口。
 *
 * 关于 Android 14 的隐式广播限制（这一段是结论，不是猜测）：
 *
 *  - **发送**隐式广播在 Android 14 上仍然完全合法，没有任何限制；
 *    被限制的是「后台应用的**静态（Manifest 声明）**接收者收不到隐式广播」。
 *  - Tasker 的「Intent Received」事件内部用的是**动态注册**（registerReceiver）
 *    的接收者，动态接收者不受这条限制，所以能正常收到我们发的事件。
 *  - 因此这里**不调用** `Intent.setPackage()`：setPackage 会把广播限定成
 *    只发给「本应用自己的接收者」，而接收方 Tasker 是另一个包，加了就永远收不到。
 *  - 代价是：任何 App 都可以注册同名 action 偷看这些事件。事件内容只是信号/制式
 *    这类非敏感信息，可以接受；如果以后要传敏感数据，得改用带签名的自定义权限。
 *
 * 另外：广播里只放基本类型与 String，绝不放 Parcelable / 自定义对象 ——
 * 跨进程传递自定义对象是最容易踩反序列化坑的地方，这里用不到也不该用。
 */
object TaskerEventSender {

    private const val TAG = "Tasker事件"

    /** 制式已切换（由调用方在切换成功后发送，签名与 [NetPilot.setMode] 对齐）。 */
    fun modeChanged(context: Context, subId: Int, modeValue: Int) {
        broadcast(context, TaskerContract.ACTION_MODE_CHANGED) {
            putExtra(TaskerContract.EXTRA_SUB_ID, subId)
            putExtra(TaskerContract.EXTRA_MODE_VALUE, modeValue)
        }
    }

    /** 进入降级态。 */
    fun downgraded(context: Context, snapshot: SignalSnapshot?) {
        broadcast(context, TaskerContract.ACTION_DOWNGRADED) {
            putTaskerSnapshot(snapshot)
            putExtra(TaskerContract.EXTRA_DOWNGRADED, true)
        }
    }

    /** 从降级态恢复。 */
    fun recovered(context: Context) {
        broadcast(context, TaskerContract.ACTION_RECOVERED) {
            putExtra(TaskerContract.EXTRA_DOWNGRADED, false)
        }
    }

    /** 默认数据卡已切换。 */
    fun dataSimChanged(context: Context, subId: Int) {
        broadcast(context, TaskerContract.ACTION_DATA_SIM_CHANGED) {
            putExtra(TaskerContract.EXTRA_SUB_ID, subId)
        }
    }

    /** 每次采样完成（监控循环驱动）。 */
    fun signalSampled(context: Context, snapshot: SignalSnapshot?) {
        broadcast(context, TaskerContract.ACTION_SIGNAL_SAMPLED) {
            putTaskerSnapshot(snapshot)
            putExtra(TaskerContract.EXTRA_DOWNGRADED, NetPilot.downgradeState().active)
        }
    }

    /**
     * 统一的发送实现：拼字段 → 附加通道标签 → sendBroadcast。
     *
     * 整个过程包在 [runCatching] 里：发送广播本身极少失败，但一旦抛异常，
     * 异常会沿着监控循环往上冒，最坏把监控服务带崩。事件发送失败只记一条日志，
     * 绝不反过来影响主流程。
     */
    private fun broadcast(context: Context, action: String, fill: Intent.() -> Unit) {
        runCatching {
            val intent = Intent(action)
            intent.fill()
            intent.putExtra(
                TaskerContract.EXTRA_CHANNEL,
                runCatching { NetPilot.channelLabel() }.getOrDefault("未知"),
            )
            context.applicationContext.sendBroadcast(intent)
            LogStore.debug(TAG, "已发送事件 $action")
        }.onFailure { t ->
            LogStore.warn(TAG, "发送事件 $action 失败：${t.message ?: t.javaClass.simpleName}")
        }
    }
}

/**
 * 把采样快照写进 Intent（只写基本类型）。
 *
 * 快照里**读不到的字段不写**对应的 extra —— 「读不到」与「读到 0」必须区分开，
 * 否则 Tasker 侧会看到 rsrp=0 这种不存在的强信号值（跟网络质量判定踩同一个坑）。
 */
internal fun Intent.putTaskerSnapshot(snapshot: SignalSnapshot?): Intent {
    if (snapshot == null) return this
    putExtra(TaskerContract.EXTRA_SUB_ID, snapshot.subId)
    putExtra(TaskerContract.EXTRA_NETWORK_TYPE, snapshot.networkType)
    snapshot.rsrp?.let { putExtra(TaskerContract.EXTRA_RSRP, it) }
    snapshot.sinr?.let { putExtra(TaskerContract.EXTRA_SINR, it) }
    snapshot.pingMs?.let { putExtra(TaskerContract.EXTRA_PING_MS, it) }
    return this
}
