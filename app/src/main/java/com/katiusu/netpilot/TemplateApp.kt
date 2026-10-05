package com.katiusu.netpilot

import android.app.Application
import com.katiusu.netpilot.bridge.HookStatusStore
import com.katiusu.netpilot.bridge.XposedServiceManager
import com.katiusu.netpilot.prefs.ConfigState
import com.katiusu.netpilot.prefs.OptionRegistry
import com.katiusu.netpilot.prefs.PrefsStore
import com.katiusu.netpilot.ui.screen.features.featureSpecs
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.priv.ControlManager
import com.katiusu.netpilot.core.tasker.TaskerGate
import kotlinx.coroutines.runBlocking

class TemplateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PrefsStore.init(this)
        ConfigState.init(this)
        OptionRegistry.registerAll(featureSpecs())
        HookStatusStore.initialize(this)
        XposedServiceManager.init()
        // NetPilot 核心：日志、监控引擎、数据卡规则接线（幂等）
        NetPilot.install(this)
        // Tasker / Locale 接口总开关（默认关）：把系统里的组件启用状态对齐到配置。
        // 关闭时这两个接收器在系统层面就是禁用的，Tasker 广播不会拉起本进程。
        //
        // 1.5.0：事件出口的接线（TaskerBridge.init）从这一行挪进了 TaskerGate.sync ——
        // 接口默认关，那就连「订阅采样/降级 StateFlow」都不要建立；等用户在设置里打开开关时，
        // ConfigState 的回调会触发 sync，那时再接线。接收器与编辑界面里仍各留一次
        // TaskerBridge.init 补调（进程可能刚被广播冷启动，那时 Application 还没跑完）。
        TaskerGate.install(this)
        pruneShizukuOrphans()
    }

    /**
     * 清掉上一次运行遗留的 Shizuku 用户服务孤儿进程。
     *
     * 每个孤儿约 40 MB 且是 root/shell 权限的独立进程，实测能累积到 4 个（≈200 MB）——
     * 它们只在「应用进程被系统杀掉时来不及 unbind」时产生，所以必须在**应用启动时**清一次，
     * 不能指望特权通道被用到时才顺手清（网络健康时那条路径根本不会走到）。
     *
     * 单开一个守护线程：清扫要绑用户服务、可能等 15 秒，绝不能占住 Application.onCreate
     * 把冷启动拖慢。失败一律静默 —— 它只是省内存，不承担任何功能。
     */
    private fun pruneShizukuOrphans() {
        Thread {
            runCatching { runBlocking { ControlManager.pruneOrphanedServices() } }
        }.apply {
            isDaemon = true
            name = "NetPilot-OrphanPrune"
        }.start()
    }
}
