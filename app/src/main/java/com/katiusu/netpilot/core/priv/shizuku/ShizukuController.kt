package com.katiusu.netpilot.core.priv.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.priv.ChannelStatus
import com.katiusu.netpilot.core.priv.ControlMethod
import com.katiusu.netpilot.core.priv.NetworkControlChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 通道：把操作转发给 [ShizukuControllerService]（跑在 shell/root 的用户进程里）。
 *
 * 绑定走 Shizuku 用户服务的标准三步（见 Shizuku API 文档 `Shizuku.UserService`）：
 * `pingBinder()` → `checkSelfPermission()` → `bindUserService(UserServiceArgs(...), conn)`。
 * 区别是本类不依赖 `BuildConfig`（本项目没开 `buildFeatures.buildConfig`），
 * debuggable / versionCode 从 PackageManager 现取。
 */
class ShizukuController(private val context: Context) : NetworkControlChannel {

    override val label: String = "Shizuku"

    override val method: ControlMethod = ControlMethod.SHIZUKU

    @Volatile
    private var service: IShizukuController? = null

    @Volatile
    private var serviceBinder: IBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "user service connected but its binder is dead")
                serviceBinder = null
                service = null
                return
            }
            serviceBinder = binder
            service = IShizukuController.Stub.asInterface(binder)
            Log.i(TAG, "Shizuku user service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku user service disconnected")
            serviceBinder = null
            service = null
        }
    }

    override fun isConnected(): Boolean = service != null && shizukuAlive()

    override suspend fun probe(): ChannelStatus {
        if (!shizukuAlive()) {
            return ChannelStatus.Unavailable(method, "Shizuku 服务未运行")
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            Log.w(TAG, "checkSelfPermission failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!granted) {
            return ChannelStatus.PermissionDenied(method, "需要在 Shizuku 中授予 NetPilot 权限")
        }
        if (!ensureServiceBinding()) {
            return ChannelStatus.Unavailable(method, "Shizuku 用户服务绑定失败（进程未启动或等待超时）")
        }
        return if (call(false) { it.probe() }) {
            pruneStaleServices()
            ChannelStatus.Available
        } else {
            ChannelStatus.Unavailable(method, "Shizuku 用户服务已连接，但特权进程内反射不可用")
        }
    }

    override suspend fun getMode(subId: Int): Int = call(-1) { it.getCurrentNetworkMode(subId) }

    override suspend fun setMode(subId: Int, mode: NetworkMode): Boolean =
        call(false) { it.setNetworkMode(subId, mode.value) }

    override suspend fun getDefaultSlot(): Int = call(-1) { it.getDefaultSlot() }

    override suspend fun setDefaultSlot(slot: Int): Boolean = call(false) { it.setDefaultSlot(slot) }

    override suspend fun activeSlots(): List<Pair<Int, Int>> = call(emptyList()) { binder ->
        binder.activeSlots().toList().chunked(2).mapNotNull { pair ->
            if (pair.size == 2) pair[0] to pair[1] else null
        }
    }

    /**
     * 请求 Shizuku 授权。
     *
     * 已用 javap 核实 Shizuku API 13.1.5 的真实签名是
     * `public static void requestPermission(int)` —— **没有** `RequestPermissionResultListener`
     * 这个类型（结果回调走 `addRequestPermissionResultListener(OnRequestPermissionResultListener)`）。
     * 仍然用 try/catch(Throwable) 兜住：设备上装的 provider 版本偏旧时可能抛 NoSuchMethodError。
     */
    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.e(TAG, "Shizuku.requestPermission($requestCode) failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun destroy() {
        try {
            service?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "user service destroy() failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            Shizuku.unbindUserService(buildUserServiceArgs(), connection, true)
        } catch (e: Throwable) {
            Log.w(TAG, "unbindUserService failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        service = null
        serviceBinder = null
    }

    /**
     * 绑定成功后清扫上一次运行遗留的用户服务孤儿进程。
     *
     * 用户服务是 root/shell 权限的独立进程（`<包名>:np_service`），客户端被系统杀掉时
     * 来不及 `unbindUserService(remove = true)`，它就会变成 PPID=1 的孤儿长期驻留，
     * 每个约 40 MB（实测累积到 4 个 / ~180 MB）。每个应用进程只做一次；
     * 清扫只是省内存，失败一律静默，绝不影响通道可用性。
     */
    private suspend fun pruneStaleServices() {
        if (!pruneOnce.compareAndSet(false, true)) return
        val removed = call(-1) { it.pruneStaleProcesses() }
        if (removed > 0) Log.i(TAG, "pruned $removed stale Shizuku user service process(es)")
    }

    private fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 15s 内等到 onServiceConnected（轮询 service 字段，回调在主线程填充）。 */
    private suspend fun ensureServiceBinding(): Boolean {
        serviceBinder?.let { if (it.pingBinder()) return true }
        if (!shizukuAlive()) return false
        return try {
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                Shizuku.bindUserService(buildUserServiceArgs(), connection)
                while (service == null) {
                    delay(POLL_INTERVAL_MS)
                }
                true
            } ?: false
        } catch (e: Throwable) {
            Log.e(TAG, "bindUserService failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 统一的 binder 调用包装。
     *
     * ⚠ 进特权进程的 binder 调用**可能永久挂住**：同步 binder transaction 没有内核级超时，
     * 用户服务里一旦死循环，调用方就再也回不来。这里用 [CALL_TIMEOUT_MS] 包一层只能保证
     * 协程在挂起状态下可被取消；对阻塞在 transact 上的同步调用它无法真正中断，
     * 所以每个调用都必须是幂等/可重入的，超时或异常一律降级成 [default]，绝不抛给 UI。
     */
    private suspend fun <T> call(default: T, block: (IShizukuController) -> T): T {
        if (service == null && !ensureServiceBinding()) {
            Log.w(TAG, "binder call skipped: user service not bound")
            return default
        }
        val controller = service ?: return default
        return try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(CALL_TIMEOUT_MS) { block(controller) }
            } ?: default
        } catch (e: Throwable) {
            Log.w(TAG, "binder call failed: ${e.javaClass.simpleName}: ${e.message}")
            default
        }
    }

    private fun buildUserServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuControllerService::class.java.name)
        )
            .processNameSuffix("np_service")
            .debuggable(isDebuggable)
            .version(versionCode)
            .tag("NetPilot")

    private val isDebuggable: Boolean
        get() = try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            false
        }

    /** Shizuku 用 version 判断用户服务是否需要重启，必须 > 0。 */
    private val versionCode: Int
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } catch (_: Throwable) {
            1
        }

    private companion object {
        const val TAG = "NetPilot"
        const val BIND_TIMEOUT_MS = 15_000L
        const val CALL_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L

        /** 孤儿进程清扫每个应用进程只做一次。 */
        val pruneOnce = AtomicBoolean(false)
    }
}
