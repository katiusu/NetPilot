package com.katiusu.netpilot.bridge

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 原名 `HookStatusStore`，现为**纯 GUI 工程的本机替代实现**。
 *
 * 原实现记录 hook 侧上报的「已生效选项」，并额外做 BOOT_COUNT / versionCode
 * 作用域校验（跨版本失效）。剥离 Xposed 后没有任何上报方，本对象退化为
 * 「本机记录已应用选项」：GUI 侧 [rememberHookApplied] / [removeKeys] 等调用点
 * 完全不变，选项摘要里的「已生效 / 未生效」文案仍然工作 ——
 * 在纯 GUI 演示里，打开某个开关即视为「已生效」（由 GUI 调用 [record]）。
 */
object HookStatusStore {

    private const val PREFS_NAME = "gui_runtime_status"
    private const val KEY_APPLIED_KEYS = "applied_keys"

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow<Set<String>>(emptySet())

    /** 已生效的选项键集合，供 Compose 以 `collectAsState()` 观察。 */
    val state: StateFlow<Set<String>> get() = _state

    /** 由 Application 调用一次。 */
    fun initialize(context: Context) {
        val storage = context.createDeviceProtectedStorageContext()
        prefs = storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = readKeys()
    }

    fun isApplied(key: String): Boolean = _state.value.contains(key)

    /** 记录若干选项为「已生效」。 */
    fun record(context: Context, keys: Collection<String>) {
        val target = prefs ?: context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        target.edit().putStringSet(KEY_APPLIED_KEYS, readKeys() + keys).apply()
        _state.value = readKeys()
    }

    /** 从「已生效」集合中移除若干选项键（选项被改动时调用）。 */
    fun removeKeys(context: Context, keys: Collection<String>) {
        val target = prefs ?: context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        target.edit().putStringSet(KEY_APPLIED_KEYS, readKeys() - keys.toSet()).apply()
        _state.value = readKeys()
    }

    fun clear(context: Context) {
        val target = prefs ?: context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
        target.edit().remove(KEY_APPLIED_KEYS).apply()
        _state.value = emptySet()
    }

    private fun readKeys(): Set<String> =
        prefs?.getStringSet(KEY_APPLIED_KEYS, emptySet())?.toSet() ?: emptySet()
}
