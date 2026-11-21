package io.github.katiusu.netpilot.core.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.mode.NetworkMode
import io.github.katiusu.netpilot.core.monitor.LogStore
import io.github.katiusu.netpilot.core.monitor.SignalSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** 本文件（两个 BroadcastReceiver + 执行器）共用的日志标签。 */
private const val TAG = "Tasker接口"

/** 命令执行结果，统一在 [sendTaskerResult] 里转成回执广播。 */
data class TaskerCommandResult(
    val ok: Boolean,
    /** 人类可读的结果或失败原因，直接进 `netpilot.result_message`。 */
    val message: String,
    /** 采样快照（GET_STATUS / SAMPLE_NOW 才有）。 */
    val snapshot: SignalSnapshot? = null,
    /** 单行状态文本（GET_STATUS / SAMPLE_NOW 才有）。 */
    val statusText: String? = null,
)

/**
 * Tasker 命令接收器（Manifest 静态注册，`exported="true"`）。
 *
 * ## Android 14 隐式广播限制：这里的处理方式与理由
 *
 * 1. **收**：本 receiver 是 Manifest 静态注册的。Android 8（API 26）起，后台应用的
 *    静态接收者收不到**隐式**广播（不带 package / component 的）。因此使用 Tasker
 *    「发送意图」时必须做**定向**：填 Package（`io.github.katiusu.netpilot`）或更稳的
 *    Class（`io.github.katiusu.netpilot.core.tasker.TaskerCommandReceiver`）。指定了
 *    package / component 的广播不在此限，后台也能送达；`adb shell am broadcast -n`
 *    指定组件同理。文档 docs/TASKER.md 里两种写法都给了。
 * 2. **回**：见 [sendTaskerResult] —— 回执**能定向就定向**：设备上装着已知的 Tasker 包
 *    （[TaskerContract.RESULT_TARGET_PACKAGES]）就 `setPackage` 只发给它；一个都装不上时
 *    才退回隐式广播，而且只带 ok / message，不带信号快照。Tasker 的「Intent Received」
 *    是动态注册的接收者，不受后台限制，定向广播同样收得到。
 * 3. **别在 onReceive 里干重活**：租到特权通道要走 su / Shizuku 往返，最长可能
 *    十几秒。所以这里用 `goAsync()` 把工作挪到 IO 协程，`onReceive` 立刻返回，
 *    最后务必 `pendingResult.finish()` 把广播标记为处理完毕。后台广播的预算约 60 秒
 *    （前台只有 10 秒），足够完成一次通道探测 + 写制式。
 */
class TaskerCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action.isNullOrBlank()) {
            LogStore.warn(TAG, "收到没有 action 的广播，已忽略")
            return
        }

        val app = context.applicationContext
        val extras = intent.extras

        // 发送方归属只用来记日志：getSentFromUid / getSentFromPackage 由系统填写、不可伪造，
        // 但它们回答的是「谁发的」，不是「谁被允许发」，不能当唯一防线。
        val fromUid = runCatching { sentFromUid }.getOrDefault(-1)
        val fromPackage = runCatching { sentFromPackage }.getOrNull()

        // 写类命令的口令校验放在 onReceive 这一层，而不是 TaskerCommandExecutor 里：
        // Locale 插件那条路径共用同一个执行器，它的 Bundle 是 Tasker 存下来的、不含口令，
        // 校验塞进执行器会顺手把插件功能打死。
        if (!TaskerAuth.authorized(app, action, extras, fromUid)) {
            LogStore.warn(
                TAG,
                "拒绝命令 " + action + "：未通过鉴权（未知 action 或口令不匹配，发送方 uid=" + fromUid +
                    " pkg=" + (fromPackage ?: "未知") + "）",
            )
            sendTaskerResult(
                app,
                TaskerCommandResult(
                    false,
                    "鉴权失败：写类命令必须带 " + TaskerContract.EXTRA_TOKEN +
                        " 且取值正确，未知 action 一律拒绝（见 docs/TASKER.md 第 3.4 节）",
                ),
            )
            return
        }

        // goAsync：见类注释第 3 条。onReceive 返回后 PendingResult 仍持有唤醒锁，
        // 直到 finish() 被调用。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 进程可能是被这条广播冷启动的，此时事件钩子还没接上，补一次（幂等）。
                runCatching { TaskerBridge.init(app) }
                    .onFailure { LogStore.warn(TAG, "Tasker 事件接线失败：${it.message ?: "未知"}") }

                val result = try {
                    TaskerCommandExecutor.execute(app, action, extras)
                } catch (t: Throwable) {
                    LogStore.error(TAG, "执行 $action 时抛异常：${t.message ?: t.javaClass.simpleName}")
                    TaskerCommandResult(false, "内部异常：${t.message ?: t.javaClass.simpleName}")
                }
                sendTaskerResult(app, result)
            } catch (t: Throwable) {
                // 最后一道防线：未捕获异常会逃出 BroadcastReceiver，最坏 ANR / 崩进程。
                LogStore.error(TAG, "回执流程异常：${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }
}

/**
 * 命令执行器：Tasker 广播与 Locale 插件共用同一份实现，避免两套逻辑漂移。
 *
 * 所有分支都返回 [TaskerCommandResult]，**不抛异常**给调用方；参数缺失/非法时
 * 记 WARN 日志并返回失败结果，绝不崩。
 */
object TaskerCommandExecutor {

    /** [extras] 在 Tasker 侧是 Intent extras，在 Locale 侧是 Bundle，两边类型一致。 */
    suspend fun execute(context: Context, action: String, extras: Bundle?): TaskerCommandResult {
        val app = context.applicationContext
        LogStore.info(TAG, "收到命令：$action")
        return when (action) {
            TaskerContract.ACTION_SET_NETWORK_MODE -> setNetworkMode(app, extras)
            TaskerContract.ACTION_SET_PRESET -> setPreset(app, extras)
            TaskerContract.ACTION_TOGGLE_AUTO_DOWNGRADE -> toggleAutoDowngrade(app)
            TaskerContract.ACTION_SET_AUTO_DOWNGRADE -> setAutoDowngrade(app, extras)
            TaskerContract.ACTION_SET_DATA_SIM -> setDataSim(app, extras)
            TaskerContract.ACTION_SWITCH_DATA_SIM -> switchDataSim(app)
            TaskerContract.ACTION_LOCK_LTE -> lockLte(app, extras)
            TaskerContract.ACTION_SAMPLE_NOW -> sampleNow(app)
            TaskerContract.ACTION_GET_STATUS -> getStatus()
            else -> fail("未知的 action：$action")
        }
    }

    // ---------------- 单条命令 ----------------

    private suspend fun setNetworkMode(context: Context, extras: Bundle?): TaskerCommandResult {
        val subId = readSubId(extras)
        if (subId < 0) return fail(missingSubId())
        if (!hasKey(extras, TaskerContract.EXTRA_MODE_VALUE)) {
            return fail("缺少参数 ${TaskerContract.EXTRA_MODE_VALUE}（Int，RIL 制式裸值，如 11 = 仅 4G）")
        }
        val modeValue = extras!!.getInt(TaskerContract.EXTRA_MODE_VALUE, -1)
        if (NetworkMode.fromValue(modeValue) == null) {
            return fail("未知制式值 $modeValue，可选 0..33（常用：11=仅 4G，9=4G/3G/2G，23=仅 5G，26/27/32=5G 自动）")
        }
        val done = NetPilot.setModeByValue(subId, modeValue)
        return if (done) {
            // 制式变化没有 StateFlow，切换成功后由这里主动上报事件。
            TaskerBridge.notifyModeChanged(context, subId, modeValue)
            ok("卡 $subId 制式已切换为 ${TaskerContract.modeLabel(modeValue)}")
        } else {
            fail("卡 $subId 切换为 ${TaskerContract.modeLabel(modeValue)} 失败：请检查 Root / Shizuku 是否已授权")
        }
    }

    private suspend fun setPreset(context: Context, extras: Bundle?): TaskerCommandResult {
        val subId = readSubId(extras)
        if (subId < 0) return fail(missingSubId())
        val preset = extras?.getString(TaskerContract.EXTRA_PRESET)?.trim().orEmpty()
        if (preset.isEmpty()) {
            return fail("缺少参数 ${TaskerContract.EXTRA_PRESET}（String，可选：5g / 5g_only / 4g / 4g_only / 3g / 2g）")
        }
        val done = NetPilot.applyPreset(subId, preset)
        return if (done) {
            // 预设名不落到裸值就报不了事件，回读一次门面的当前值。
            val applied = runCatching { NetPilot.currentModeValue(subId) }.getOrDefault(-1)
            if (applied >= 0) TaskerBridge.notifyModeChanged(context, subId, applied)
            ok("卡 $subId 已按预设「$preset」切换制式")
        } else {
            fail("预设「$preset」不识别或切换失败：可选 5g / 5g_only / 4g / 4g_only / 3g / 2g，并确认特权通道可用")
        }
    }

    private fun toggleAutoDowngrade(context: Context): TaskerCommandResult {
        val next = NetPilot.toggleAutoDowngrade(context)
        return ok(if (next) "网络质量自动降级已开启" else "网络质量自动降级已关闭")
    }

    private fun setAutoDowngrade(context: Context, extras: Bundle?): TaskerCommandResult {
        if (!hasKey(extras, TaskerContract.EXTRA_ENABLED)) {
            return fail("缺少参数 ${TaskerContract.EXTRA_ENABLED}（Boolean，true / false）")
        }
        val enabled = extras!!.getBoolean(TaskerContract.EXTRA_ENABLED, false)
        NetPilot.setAutoDowngrade(context, enabled)
        return ok(if (enabled) "网络质量自动降级已开启" else "网络质量自动降级已关闭")
    }

    private suspend fun setDataSim(context: Context, extras: Bundle?): TaskerCommandResult {
        val subId = readSubId(extras)
        if (subId < 0) return fail(missingSubId())
        val done = NetPilot.setDefaultDataSubId(context, subId)
        return if (done) {
            TaskerBridge.notifyDataSimChanged(context, subId)
            ok("默认数据卡已切到 subId $subId")
        } else {
            fail("切换默认数据卡到 subId $subId 失败：subId 可能不存在，或特权通道不可用")
        }
    }

    private suspend fun switchDataSim(context: Context): TaskerCommandResult {
        val after = NetPilot.switchDataSim(context)
        return if (after >= 0) {
            TaskerBridge.notifyDataSimChanged(context, after)
            ok("默认数据卡已互换到 subId $after")
        } else {
            fail("无法互换默认数据卡：只有一张卡，或特权通道不可用")
        }
    }

    private suspend fun lockLte(context: Context, extras: Bundle?): TaskerCommandResult {
        if (!hasKey(extras, TaskerContract.EXTRA_LOCK)) {
            return fail("缺少参数 ${TaskerContract.EXTRA_LOCK}（Boolean，true=锁仅 4G，false=恢复）")
        }
        val lock = extras!!.getBoolean(TaskerContract.EXTRA_LOCK, false)
        val done = NetPilot.lockLte(context, lock)
        return if (done) {
            ok(if (lock) "默认数据卡已锁定为 ${TaskerContract.modeLabel(11)}" else "默认数据卡已恢复为运营商默认制式")
        } else {
            fail("锁定 / 恢复失败：拿不到默认数据卡，或特权通道不可用")
        }
    }

    private suspend fun sampleNow(context: Context): TaskerCommandResult {
        val snapshot = runCatching { NetPilot.sampleNow(context) }.getOrElse { t ->
            return fail("采样失败：${t.message ?: t.javaClass.simpleName}")
        }
        val text = runCatching { NetPilot.statusText() }.getOrDefault("采样已完成")
        return ok(text, snapshot, text)
    }

    private fun getStatus(): TaskerCommandResult {
        val snapshot = runCatching { NetPilot.snapshot() }.getOrNull()
        val text = runCatching { NetPilot.statusText() }.getOrDefault("状态读取失败")
        return ok(text, snapshot, text)
    }

    // ---------------- 小工具 ----------------

    private fun ok(
        message: String,
        snapshot: SignalSnapshot? = null,
        statusText: String? = null,
    ): TaskerCommandResult {
        LogStore.info(TAG, message)
        return TaskerCommandResult(true, message, snapshot, statusText)
    }

    private fun fail(message: String): TaskerCommandResult {
        LogStore.warn(TAG, message)
        return TaskerCommandResult(false, message)
    }

    private fun hasKey(extras: Bundle?, key: String): Boolean =
        extras != null && extras.containsKey(key)

    /** 读 subId；缺失或为负都返回 -1（Android 的 subId 恒为非负）。 */
    private fun readSubId(extras: Bundle?): Int {
        if (!hasKey(extras, TaskerContract.EXTRA_SUB_ID)) return -1
        return extras!!.getInt(TaskerContract.EXTRA_SUB_ID, -1).takeIf { it >= 0 } ?: -1
    }

    private fun missingSubId(): String =
        "缺少或非法的参数 ${TaskerContract.EXTRA_SUB_ID}（Int，SIM 卡的 subId，例如 1 / 2）"
}

/**
 * 写类命令的口令校验（见 [TaskerContract.WRITE_ACTIONS]）。
 *
 * 为什么不用 `Binder.getCallingUid()`：广播派发路径上没有「调用者」，那个 API 在这里取到的
 * 是本进程自己的 uid —— 拿它做鉴权等于永远放行，是假安全。uid 只从
 * [BroadcastReceiver.getSentFromUid] 取，而且只用于日志归属和「自身 uid 免口令」这条快速路径。
 */
internal object TaskerAuth {

    fun authorized(context: Context, action: String, extras: Bundle?, fromUid: Int): Boolean {
        // 不在接收侧全集里的 action 一律拒绝（fail closed）：以后往 TaskerCommandExecutor 里加了
        // 新分支却忘了登记 RECEIVE_ACTIONS 时，这里得到的是「拒绝」，而不是「不在写类集合里 ⇒ 免口令」。
        if (action !in TaskerContract.RECEIVE_ACTIONS) {
            LogStore.warn(TAG, "拒绝未知 action：$action")
            return false
        }
        // 读类命令（查询状态 / 立即采样）不改任何状态，放行。
        if (action !in TaskerContract.WRITE_ACTIONS) return true

        // 自身 uid 免口令：同 uid 的内部调用没有跨应用风险，也不该被迫维护一份口令。
        // sentFromUid 由系统填写、不可伪造；拿不到（-1）时不当成自身。
        if (fromUid >= 0 && fromUid == Process.myUid()) return true

        val expected = TaskerGate.token(context)
        // 口令本该在打开接口时就生成，这里是空的只可能是配置被外部改坏 ——
        // 那种情况宁可全拒，也不要放行。
        if (expected.isEmpty()) return false

        val given = runCatching { extras?.getString(TaskerContract.EXTRA_TOKEN) }.getOrNull()
            ?: return false
        // 定长比较：口令是 UUID，逐字节比完不提前返回，不给时序攻击留缝。
        // 两边都显式 UTF-8：默认字符集随系统区域设置变，而存储值与发送方不保证同一次运行环境。
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }
}

/**
 * 把执行结果发成回执广播（action = [TaskerContract.ACTION_RESULT]）。
 *
 * 关键取舍：**能定向就定向，定不了就脱敏**。
 *
 *  - 已知的 Tasker 包装着 ⇒ `setPackage` 定向发送，别的 App 收不到，快照可以照发。
 *  - 一个都装不上 ⇒ 退回隐式广播（用户可能把 Tasker 装在别的包名下，或用「Intent Received」
 *    手搓调试），但**只带 ok / message**：快照、状态文本、特权通道名一律不出去。
 *    代价是这类极端场景下 Tasker 拿不到快照，换来的是「注册了同名 action 的 App 一个都偷不到」。
 */
internal fun sendTaskerResult(context: Context, result: TaskerCommandResult) {
    runCatching {
        val app = context.applicationContext
        val target = TaskerContract.resultTarget(app)
        val intent = TaskerContract.resultIntent(result.ok, result.message)
        if (target != null) {
            intent.putTaskerSnapshot(result.snapshot)
            result.statusText?.let { intent.putExtra(TaskerContract.EXTRA_STATUS_TEXT, it) }
            intent.putExtra(
                TaskerContract.EXTRA_DOWNGRADED,
                runCatching { NetPilot.downgradeState().active }.getOrDefault(false),
            )
            intent.putExtra(
                TaskerContract.EXTRA_CHANNEL,
                runCatching { NetPilot.channelLabel() }.getOrDefault("未知"),
            )
            intent.setPackage(target)
        }
        app.sendBroadcast(intent)
    }.onFailure { t ->
        LogStore.warn(TAG, "回执广播发送失败：${t.message ?: t.javaClass.simpleName}")
    }
}
