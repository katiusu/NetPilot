package io.github.katiusu.netpilot.core.tasker

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.katiusu.netpilot.core.NetPilot
import io.github.katiusu.netpilot.core.monitor.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 本文件日志标签。 */
private const val TAG_LOCALE = "Locale插件"

/**
 * 允许触发插件动作的来源包名。
 *
 * 为什么这里不用口令（见 [TaskerContract.EXTRA_TOKEN]）：插件配置的 Bundle 是 **Tasker 自己**
 * 存下来再回放的，插件没有机会往里塞一个只有本应用知道的值，口令方案覆盖不到这条路。
 * 能用的身份信息只有系统填写的发送方包名，所以这里改成来源白名单。
 *
 * 为什么额外放行 `com.android.shell`：docs/TASKER.md 5.3 用 adb 模拟插件广播来排错，那条路
 * 的身份是 shell（uid 2000）。包名由系统填写、普通 App 冒充不了，而能开 adb 的人本来就能直接
 * `am broadcast` 或 `su`，放行它不扩大实际攻击面。
 */
private val TRUSTED_FIRE_PACKAGES: Set<String> =
    TaskerContract.RESULT_TARGET_PACKAGES.toSet() + "com.android.shell"

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
 *
 * 鉴权：口令（[TaskerContract.EXTRA_TOKEN]）挡不住这条路 —— Bundle 由 Tasker 回放，插件塞不进自己的
 * 秘密。这里改用**来源白名单**（[TRUSTED_FIRE_PACKAGES]），只认 Tasker 与 adb shell 两个包名。
 */
class LocaleFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action.isNullOrBlank()) {
            LogStore.warn(TAG_LOCALE, "收到没有 action 的广播，已忽略")
            return
        }
        // 来源白名单：这个 receiver 是 exported 的，而它的 Bundle 里没有、也不可能有口令，
        // 只剩「谁发的」这一条判据。getSentFromPackage 由系统填写、不可伪造。
        val fromPackage = runCatching { sentFromPackage }.getOrNull()
        val fromUid = runCatching { sentFromUid }.getOrDefault(-1)
        if (!isTrustedSource(action, fromPackage, fromUid)) {
            rejectUntrusted(action)
            return
        }

        val bundle = LocalePluginContract.readBundle(intent)
        when (action) {
            LocalePluginContract.ACTION_FIRE_SETTING -> handleFire(context, bundle)
            LocalePluginContract.ACTION_QUERY_CONDITION -> handleQuery(bundle)
            else -> LogStore.warn(TAG_LOCALE, "不支持的 action：$action")
        }
    }

    /**
     * 来源是否可信。
     *
     * 包名为 null 时**放行并记 WARN**：getSentFromPackage 在少数 ROM / 极早期的广播路径上会返回
     * null，一律拒绝会让正常用户的插件静默失效，而这个 receiver 只执行「Tasker 已经保存好的配置」，
     * 本身不引入新的权限面。真正要挡的是「任意 App 带着自己包名乱发」，那种请求包名一定非空。
     */
    private fun isTrustedSource(action: String, fromPackage: String?, fromUid: Int): Boolean {
        if (fromPackage == null) {
            LogStore.warn(
                TAG_LOCALE,
                "无法确定 $action 的发送方（uid=" + fromUid + "），按兼容处理放行",
            )
            return true
        }
        if (fromPackage in TRUSTED_FIRE_PACKAGES) return true
        LogStore.warn(
            TAG_LOCALE,
            "拒绝来自 " + fromPackage + "（uid=" + fromUid + "）的 $action：只接受 Tasker 或 adb shell",
        )
        return false
    }

    /** 拒绝时按 Locale 协议回「没做事」：动作回 CANCELED，条件回不满足。 */
    private fun rejectUntrusted(action: String) {
        runCatching {
            setResultCode(
                if (action == LocalePluginContract.ACTION_QUERY_CONDITION) {
                    LocalePluginContract.RESULT_CONDITION_UNSATISFIED
                } else {
                    Activity.RESULT_CANCELED
                }
            )
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
