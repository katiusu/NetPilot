package io.github.katiusu.netpilot.ui.component.pref

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.katiusu.netpilot.device.DeviceContext
import io.github.katiusu.netpilot.device.DeviceType
import io.github.katiusu.netpilot.prefs.ConfigState
import io.github.katiusu.netpilot.prefs.OptionRegistry
import io.github.katiusu.netpilot.prefs.OptionSpec
import io.github.katiusu.netpilot.bridge.HookStatusStore
import io.github.katiusu.netpilot.bridge.XposedServiceManager

@Composable
fun rememberEffectiveDeviceType(): DeviceType {
    val override = ConfigState.string(DeviceContext.KEY_DEVICE_TYPE, DeviceType.OVERRIDE_AUTO)
    return DeviceType.fromKey(override) ?: DeviceContext.detected.type
}

@Composable
fun rememberDeviceScopeEnabled(spec: OptionSpec): Boolean {
    val scope = spec.deviceScope
    if (scope.isNullOrEmpty()) return true
    return rememberEffectiveDeviceType() in scope
}

@Composable
fun rememberDependencyEnabled(spec: OptionSpec): Boolean {
    val dependencyKey = spec.dependsOn ?: return true
    val dependencyDefault = OptionRegistry.find(dependencyKey)?.defaultBoolean ?: false
    val dependencyValue = ConfigState.bool(dependencyKey, dependencyDefault)
    return if (spec.dependsOnValue) dependencyValue else !dependencyValue
}

@Composable
fun rememberOptionEnabled(spec: OptionSpec): Boolean =
    rememberDependencyEnabled(spec) && rememberDeviceScopeEnabled(spec)

@Composable
fun rememberHookApplied(key: String): Boolean {
    val applied by HookStatusStore.state.collectAsState()
    return key in applied
}

fun ensureScopeFor(spec: OptionSpec) {
    if (spec.targetPackages.isNotEmpty()) {
        XposedServiceManager.ensureScope(spec.targetPackages)
    }
}

fun recordOptionApplied(context: android.content.Context, spec: OptionSpec) {
    HookStatusStore.record(context, listOf(spec.key))
}
