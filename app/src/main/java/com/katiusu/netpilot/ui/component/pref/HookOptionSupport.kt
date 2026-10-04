package com.katiusu.netpilot.ui.component.pref

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.katiusu.netpilot.device.DeviceContext
import com.katiusu.netpilot.device.DeviceType
import com.katiusu.netpilot.prefs.ConfigState
import com.katiusu.netpilot.prefs.OptionRegistry
import com.katiusu.netpilot.prefs.OptionSpec
import com.katiusu.netpilot.bridge.HookStatusStore
import com.katiusu.netpilot.bridge.XposedServiceManager

/**
 * 当前生效的设备形态：优先设置页「当前设备类型」的覆盖值，否则使用模块自动判定。
 *
 * 读取 [ConfigState]，因此更改设备类型时会实时重组刷新，无需重启页面。
 */
@Composable
fun rememberEffectiveDeviceType(): DeviceType {
    val override = ConfigState.string(DeviceContext.KEY_DEVICE_TYPE, DeviceType.OVERRIDE_AUTO)
    return DeviceType.fromKey(override) ?: DeviceContext.detected.type
}

/**
 * 设备形态是否在 [OptionSpec.deviceScope] 白名单内；未声明白名单（null / 空）视为各设备通用。
 *
 * 非白名单设备返回 false，用于把设备独占功能**禁用而不隐藏**。
 */
@Composable
fun rememberDeviceScopeEnabled(spec: OptionSpec): Boolean {
    val scope = spec.deviceScope
    if (scope.isNullOrEmpty()) return true
    return rememberEffectiveDeviceType() in scope
}

/** 解析依赖项：依赖项满足条件时组件启用。 */
@Composable
fun rememberDependencyEnabled(spec: OptionSpec): Boolean {
    val dependencyKey = spec.dependsOn ?: return true
    val dependencyDefault = OptionRegistry.find(dependencyKey)?.defaultBoolean ?: false
    val dependencyValue = ConfigState.bool(dependencyKey, dependencyDefault)
    return if (spec.dependsOnValue) dependencyValue else !dependencyValue
}

/**
 * 组件是否可用：同时满足「依赖项」与「设备形态白名单」。
 *
 * 各 Hook 卡片统一以它作为 `enabled`，保证设备独占功能在非白名单设备上禁用（灰显）而非隐藏。
 */
@Composable
fun rememberOptionEnabled(spec: OptionSpec): Boolean =
    rememberDependencyEnabled(spec) && rememberDeviceScopeEnabled(spec)

/**
 * 该配置键是否已在目标进程生效（由目标进程广播回报，App 侧按版本 + 开机号作用域持久化）。
 *
 * 读取 [HookStatusStore.state]，目标进程重新回报后自动刷新。
 */
@Composable
fun rememberHookApplied(key: String): Boolean {
    val applied by HookStatusStore.state.collectAsState()
    return key in applied
}

/**
 * 选项被启用时，自动为未授权的作用域目标发起申请。
 */
fun ensureScopeFor(spec: OptionSpec) {
    if (spec.targetPackages.isNotEmpty()) {
        XposedServiceManager.ensureScope(spec.targetPackages)
    }
}

/**
 * 纯 GUI 工程的「已生效」记录：调用位置与 [ensureScopeFor] 一一对应
 * （即「选项离开默认值」的时机），使摘要里的「已生效 / 未生效」文案
 * 与开关状态保持一致。原模板中该状态由 hook 侧广播回报，此处改为本机记录。
 */
fun recordOptionApplied(context: android.content.Context, spec: OptionSpec) {
    HookStatusStore.record(context, listOf(spec.key))
}
