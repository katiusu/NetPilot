package com.katiusu.netpilot

import android.app.Application
import com.katiusu.netpilot.bridge.HookStatusStore
import com.katiusu.netpilot.bridge.XposedServiceManager
import com.katiusu.netpilot.prefs.ConfigState
import com.katiusu.netpilot.prefs.OptionRegistry
import com.katiusu.netpilot.prefs.PrefsStore
import com.katiusu.netpilot.ui.screen.features.featureSpecs
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.tasker.TaskerBridge

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
        // Tasker/Locale 事件上报（订阅 MonitorEngine 的 StateFlow，幂等）
        TaskerBridge.init(this)
    }
}
