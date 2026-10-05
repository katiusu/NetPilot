package com.katiusu.netpilot.core.tasker

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.katiusu.netpilot.core.monitor.LogStore
import com.katiusu.netpilot.prefs.ConfigState

/**
 * Tasker / Locale 接口的总开关落点。
 *
 * 为什么不是「收到命令再判开关」：那样只是让命令不生效，广播本身照样会唤醒本应用进程
 * （冷启动 + 读配置 + 写日志），对不用自动化的用户就是纯浪费。这里改用
 * [PackageManager.setComponentEnabledSetting] 把三个 exported 组件在**系统层面**禁用，
 * 系统连广播都不会派发过来，后台开销严格为零 —— 不是「变小」，是「没有」。
 *
 * 默认关闭：manifest 里这三个组件都写了 `android:enabled="false"`，所以装完不打开任何
 * 东西也不会被唤醒一次。用户打开开关后这里把状态显式置为 ENABLED；显式状态由系统持久化
 * （卸载或「清除数据」才回到 manifest 默认值）。
 */
object TaskerGate {

    /** 开关的配置键。 */
    const val KEY_ENABLED = "np_tasker_enabled"

    /** 默认关闭：自动化接口是少数人用的能力，默认不该带来任何后台开销。 */
    const val DEFAULT_ENABLED = false

    /**
     * 随开关一起启用/禁用的组件（值 = manifest 里 `android:name` 除包名之外的部分）。
     *
     * 前两个是接收器 —— 真正会被广播唤醒的东西。第三个是插件配置界面：Tasker 的「插件」
     * 列表靠它出现，接口关着时它还留在列表里、点了却执行不了，比直接消失更让人困惑。
     *
     * 配置界面用**类名字符串**而不是 `TaskerEditActivity::class.java`：它在
     * `com.katiusu.netpilot.tasker` 包，而那个包已经 import 了本包（core.tasker），
     * 直接引用会形成包级循环依赖。字符串与 manifest 必须一致；万一不一致，后果也只是
     * 这个界面不被禁用（两个接收器仍禁得掉），不会影响「零唤醒」这个结论。
     */
    private val COMPONENTS = listOf(
        ".core.tasker.TaskerCommandReceiver",
        ".core.tasker.LocaleFireReceiver",
        ".tasker.TaskerEditActivity",
    )

    /**
     * 应用启动时调用一次：注册配置回调，并把系统里的组件状态对齐到当前配置。
     *
     * 为什么启动时还要对齐一次：`setComponentEnabledSetting` 的状态虽然会被系统持久化，
     * 但「清除数据」「换机恢复备份」之后会回到 manifest 默认值，而组件处于禁用状态是
     * 「零唤醒」的前提，不能只依赖用户上次点开关那一下。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        ConfigState.observe(KEY_ENABLED) { sync(app) }
        sync(app)
    }

    /** 把组件的启用状态对齐到当前配置；已经一致时不做任何写入。 */
    fun sync(context: Context) {
        val app = context.applicationContext
        val enabled = ConfigState.bool(KEY_ENABLED, DEFAULT_ENABLED)
        val pm = app.packageManager
        COMPONENTS.forEach { suffix ->
            runCatching {
                apply(pm, ComponentName(app.packageName, app.packageName + suffix), enabled)
            }
        }
        // 接口开着才接线事件出口。为什么「关着时连订阅都不建立」，而不是只靠发送侧判断：
        // 两道门都要 —— 组件禁用只挡住「外面叫醒我们」，发送侧的门控（TaskerEventSender.broadcast）
        // 才挡住「我们主动播出去」；而这里少建立两个常驻收集协程，关着时这块后台开销严格为零。
        if (enabled) TaskerBridge.init(app)
    }

    /**
     * 当前接口是否开启。事件出口（[TaskerEventSender]）在每次发送前查一次。
     *
     * 为什么用「查询」而不是「订阅」：发送是低频动作，一次 `ConfigState.bool` 只是读内存里的
     * map；订阅要多存一份状态、还要在开关变化时同步它，一旦同步漏了，表现就是「接口关了、
     * 事件照发」——最不容易发现的那种坏法。
     */
    fun isEnabled(context: Context): Boolean {
        val app = context.applicationContext
        runCatching { ConfigState.init(app) }
        return ConfigState.bool(KEY_ENABLED, DEFAULT_ENABLED)
    }

    private fun apply(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        val current = pm.getComponentEnabledSetting(component)
        val desired = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (current == desired) return
        // 要关、而当前是 DEFAULT：manifest 里这三个组件默认就是 enabled="false"，
        // DEFAULT 已经等价于关掉，没必要多写一次 IPC（每次冷启动都会走到这里）。
        if (!enabled && current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return
        pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
        LogStore.info(TAG, "${component.className} 已${if (enabled) "启用" else "禁用"}")
    }

    private const val TAG = "Tasker接口"
}
