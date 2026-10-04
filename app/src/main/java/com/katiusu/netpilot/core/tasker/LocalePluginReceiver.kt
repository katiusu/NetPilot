package com.katiusu.netpilot.core.tasker

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.monitor.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 本文件日志标签。 */
private const val TAG_LOCALE = "Locale插件"

/**
 * Locale 插件协议的常量（Tasker 的「插件」动作就是兼容 Locale 这套约定）。
 *
 * 这些字符串是第三方协议硬编码的，不能改；集中放这里是为了让接收器与编辑界面
 * 引用同一份定义。
 */
object LocalePluginContract {

    /** Tasker 打开插件配置界面时发给插件 Activity 的 action。 */
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"

    /** Tasker 执行插件动作时发给插件 Receiver 的 action。 */
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"

    /** Tasker 查询插件条件时发给插件 Receiver 的 action。 */
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"

    /** Intent extra：承载插件配置的 Bundle（编辑界面回传、Tasker 执行时发回）。 */
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"

    /** Intent extra：给 Tasker 列表显示的一行摘要。 */
    const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    /** 条件查询结果码：条件成立。 */
    const val RESULT_CONDITION_SATISFIED = 16

    /** 条件查询结果码：条件不成立（判断不了时也回这个，宁可不动手也不要误判）。 */
    const val RESULT_CONDITION_UNSATISFIED = 17

    /**
     * 从 Intent 里取插件 Bundle。
     *
     * 包 [runCatching] 的理由：extras 是跨进程传进来的，类型不符合预期时
     * `getBundleExtra` 会抛 ClassCastException / BadParcelableException。
     * 插件接收器必须容忍宿主（Tasker）版本差异，拿不到就当没配置。
     */
    fun readBundle(intent: Intent?): Bundle? = runCatching {
        intent?.getBundleExtra(EXTRA_BUNDLE)
    }.getOrNull()
}

/**
 * Locale 插件 Receiver（Manifest 静态注册，`exported="true"`）。
 *
 * 两条路径：
 *  - `FIRE_SETTING`：执行动作。参数从 [LocalePluginContract.EXTRA_BUNDLE] 里读，
 *    与 Tasker 广播走**同一个执行器** [TaskerCommandExecutor]，能力完全一致。
 *  - `QUERY_CONDITION`：条件判断。只读内存里的状态（同步、瞬时），所以不需要
 *    goAsync；判断不了时回 [LocalePluginContract.RESULT_CONDITION_UNSATISFIED]。
 *
 * Bundle 里只放 String / Int / Boolean（见 [TaskerEditActivity]），跨进程最稳。
 */
class LocaleFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action.isNullOrBlank()) {
            LogStore.warn(TAG_LOCALE, "收到没有 action 的广播，已忽略")
            return
        }
        val bundle = LocalePluginContract.readBundle(intent)
        when (action) {
            LocalePluginContract.ACTION_FIRE_SETTING -> handleFire(context, bundle)
            LocalePluginContract.ACTION_QUERY_CONDITION -> handleQuery(bundle)
            else -> LogStore.warn(TAG_LOCALE, "不支持的 action：$action")
        }
    }

    // ---------------- FIRE ----------------

    private fun handleFire(context: Context, bundle: Bundle?) {
        val app = context.applicationContext
        val taskerAction = bundle?.getString(TaskerContract.EXTRA_PLUGIN_ACTION)?.trim().orEmpty()
        if (taskerAction.isEmpty()) {
            LogStore.warn(TAG_LOCALE, "FIRE_SETTING 缺少 ${TaskerContract.EXTRA_PLUGIN_ACTION}，已忽略")
            // 有序广播的结果码：没有可执行的动作，明确回失败，Tasker 会显示错误。
            runCatching { setResultCode(Activity.RESULT_CANCELED) }
            return
        }

        // 与 Tasker 广播一样的理由：执行要经 su / Shizuku，不能在 onReceive 里同步做。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { TaskerBridge.init(app) }
                    .onFailure { LogStore.warn(TAG_LOCALE, "Tasker 事件接线失败：${it.message ?: "未知"}") }
                val result = try {
                    TaskerCommandExecutor.execute(app, taskerAction, bundle)
                } catch (t: Throwable) {
                    LogStore.error(TAG_LOCALE, "执行 $taskerAction 时抛异常：${t.message ?: t.javaClass.simpleName}")
                    TaskerCommandResult(false, "内部异常：${t.message ?: t.javaClass.simpleName}")
                }
                // Locale 协议：FIRE_SETTING 是有序广播，插件用结果码报告成功/失败。
                runCatching {
                    pendingResult.setResultCode(
                        if (result.ok) Activity.RESULT_OK else Activity.RESULT_CANCELED
                    )
                }
                runCatching { pendingResult.setResultData(result.message) }
                // 顺带发一条同样的 Tasker 回执，方便用「Intent Received」调试。
                sendTaskerResult(app, result)
            } catch (t: Throwable) {
                LogStore.error(TAG_LOCALE, "回执流程异常：${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    // ---------------- QUERY ----------------

    private fun handleQuery(bundle: Bundle?) {
        val condition = bundle?.getString(TaskerContract.EXTRA_PLUGIN_CONDITION)?.trim().orEmpty()
        if (condition.isEmpty()) {
            LogStore.warn(TAG_LOCALE, "QUERY_CONDITION 缺少 ${TaskerContract.EXTRA_PLUGIN_CONDITION}，回不满足")
            runCatching { setResultCode(LocalePluginContract.RESULT_CONDITION_UNSATISFIED) }
            return
        }
        val satisfied = runCatching {
            when (condition) {
                TaskerContract.CONDITION_DOWNGRADED -> NetPilot.downgradeState().active
                TaskerContract.CONDITION_AUTO_DOWNGRADE_ENABLED -> NetPilot.autoDowngradeEnabled()
                TaskerContract.CONDITION_MONITOR_RUNNING -> NetPilot.monitorRunning()
                else -> {
                    LogStore.warn(TAG_LOCALE, "未知条件「$condition」")
                    false
                }
            }
        }.getOrElse { t ->
            LogStore.warn(TAG_LOCALE, "条件「$condition」判断失败：${t.message ?: t.javaClass.simpleName}")
            false
        }
        runCatching {
            setResultCode(
                if (satisfied) LocalePluginContract.RESULT_CONDITION_SATISFIED
                else LocalePluginContract.RESULT_CONDITION_UNSATISFIED
            )
        }
        LogStore.debug(TAG_LOCALE, "条件查询「$condition」→ $satisfied")
    }
}
