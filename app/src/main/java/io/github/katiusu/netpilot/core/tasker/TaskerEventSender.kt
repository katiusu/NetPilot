package io.github.katiusu.netpilot.core.tasker

import android.content.Context
import android.content.Intent
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.monitor.LogStore
import io.github.katiusu.netpilot.core.monitor.SignalSnapshot

/**
 * NetPilot → Tasker 的事件发送出口。
 *
 * 关于 Android 14 的隐式广播限制（这一段是结论，不是猜测）：
 *
 *  - **发送**隐式广播在 Android 14 上仍然完全合法，没有任何限制；
 *    被限制的是「后台应用的**静态（Manifest 声明）**接收者收不到隐式广播」。
 *  - Tasker 的「Intent Received」事件内部用的是**动态注册**（registerReceiver）
 *    的接收者，动态接收者不受这条限制，所以能正常收到我们发的事件。
 *  - 定向发送用 `setPackage(Tasker 包名)`：它只把广播限定给**指定的那个包**，不是限定给本应用。
 *    「Tasker 是另一个包，加了就收不到」只对 `setPackage(自己包名)` 成立，对 `setPackage(Tasker 包名)`
 *    恰好相反 —— 那才是唯一能让 Tasker 收到、同时别的 App 收不到的写法。
 *  - 所以这里**能定向就定向，定不了就丢弃**：设备上找不到已知 Tasker 包时不发（隐式发送等于把
 *    信号快照播给同机所有注册了同名 action 的 App）；事件是通知性质的，脱敏后也失去意义。
 *    丢弃只记一条 DEBUG —— 没装 Tasker 的用户每次采样都会走到这里，不该变成噪音。
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
        // 1.5.0：接口关着时一个事件都不发。
        //
        // 为什么不靠 [TaskerGate] 的组件禁用就够了：那三个 `setComponentEnabledSetting` 管的是
        // 「外面能不能叫醒我们」（收广播），管不了「我们主动播出去」。本工程有五条发送路径
        // （制式 / 降级 / 恢复 / 换卡 / 每次采样），它们全部汇到这一个函数；接口关着时旧版照样
        // 每次采样都 `sendBroadcast` 一次 —— 用户侧只是「Tasker 收不到」，而广播本身仍会唤醒
        // 别的监听者、仍算一次系统调用，还会往日志页写一行「已发送事件」。
        // 门控放在这个唯一收口点上：一个判断覆盖全部五条路径，也不存在漏改某条调用链的可能。
        if (!TaskerGate.isEnabled(context)) return
        runCatching {
            val app = context.applicationContext
            // 定向目标：与回执共用同一份判定（TaskerContract.resultTarget）。
            val target = TaskerContract.resultTarget(app)
            if (target == null) {
                // 不降级成隐式广播：那条路的代价是把 RSRP/SINR/ping 播给同机任意 App。
                LogStore.debug(TAG, "未找到 Tasker 包，事件 $action 已丢弃")
                return@runCatching
            }
            val intent = Intent(action)
            intent.fill()
            intent.putExtra(
                TaskerContract.EXTRA_CHANNEL,
                runCatching { NetPilot.channelLabel() }.getOrDefault("未知"),
            )
            intent.setPackage(target)
            app.sendBroadcast(intent)
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
