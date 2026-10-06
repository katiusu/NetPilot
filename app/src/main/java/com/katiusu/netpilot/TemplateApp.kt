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
     *
     * 1.5.3：这条线程改成**有界重试**。为什么必须重试：它在 Application.onCreate 里就起来了，
     * 而开机/升级冷启动那一刻 Shizuku 服务端常常还没起来（或被系统按住），绑定必然失败；
     * 旧实现「失败也算做过」，于是唯一一次机会被一次注定失败的尝试烧掉，而进程又有
     * MonitorService 的 START_STICKY + 15 分钟心跳长期活着 —— 再也没有第二次 onCreate，
     * 用户后来正常用起 Shizuku 也清不掉上次遗留的孤儿（实测本机有一个从开机留到现在、
     * 约 59 MB 的 `:np_service`）。
     *
     * 4 次尝试：立刻 / 30 秒 / 2 分钟 / 5 分钟；任何一次**真的执行成功**（返回 >= 0）就停，
     * Shizuku 一直没起来时每次只是一次很便宜的探测，期间进程本来也要活着。
     */
    private fun pruneShizukuOrphans() {
        Thread {
            val delaysMs = longArrayOf(0L, 30_000L, 120_000L, 300_000L)
            var attempt = 0
            var done = false
            while (!done && attempt < delaysMs.size) {
                if (delaysMs[attempt] > 0L) runCatching { Thread.sleep(delaysMs[attempt]) }
                val result = runCatching { runBlocking { ControlManager.pruneOrphanedServices() } }
                    .getOrDefault(-1)
                // >= 0 表示清扫命令真的跑过了（哪怕清了 0 个），不必再试；-1 表示没执行，稍后重试。
                done = result >= 0
                attempt++
            }
        }.apply {
            isDaemon = true
            name = "NetPilot-OrphanPrune"
        }.start()
    }
}
