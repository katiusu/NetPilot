package com.katiusu.netpilot.core.priv.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.katiusu.netpilot.core.mode.NetworkMode
import com.katiusu.netpilot.core.priv.AuthStore
import com.katiusu.netpilot.core.priv.ChannelStatus
import com.katiusu.netpilot.core.priv.ControlMethod
import com.katiusu.netpilot.core.priv.NetworkControlChannel
import com.katiusu.netpilot.core.priv.WriteDiag
import kotlinx.coroutines.CancellationException
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
                // 只写 logcat 是不够的：这是「绑定成功但通道不能用」的典型形态，日志页也要看得见。
                Log.w(TAG, "user service connected but its binder is dead")
                WriteDiag.failure("shizuku 用户服务已连接，但 binder 已经死了（pingBinder 返回 false）")
                serviceBinder = null
                service = null
                return
            }
            serviceBinder = binder
            service = IShizukuController.Stub.asInterface(binder)
            Log.i(TAG, "Shizuku user service connected")
            WriteDiag.detail("shizuku 用户服务（:np_service）已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku user service disconnected")
            WriteDiag.failure("shizuku 用户服务连接断开（进程被杀或 Shizuku 重启）")
            serviceBinder = null
            service = null
        }
    }

    override fun isConnected(): Boolean = service != null && shizukuAlive()

    override suspend fun probe(): ChannelStatus {
        // 「哪里不行」必须逐分支写进日志页：Shizuku 的四种失败形态（没装/没授权/绑不上/绑上了
        // 但反射不可用）在界面上都只是「通道不可用」，用户没法据此自己修。
        if (!shizukuAlive()) {
            WriteDiag.always("shizuku 探测：Shizuku 服务未运行（pingBinder 失败或抛异常）")
            return ChannelStatus.Unavailable(method, "Shizuku 服务未运行")
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            Log.w(TAG, "checkSelfPermission failed: ${e.javaClass.simpleName}: ${e.message}")
            WriteDiag.failure(
                "shizuku 探测：checkSelfPermission 抛异常 ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
        if (!granted) {
            WriteDiag.always("shizuku 探测：应用还没拿到 Shizuku 权限（需要在 Shizuku 里授权）")
            return ChannelStatus.PermissionDenied(method, "需要在 Shizuku 中授予 NetPilot 权限")
        }
        if (!ensureServiceBinding()) {
            WriteDiag.failure(
                "shizuku 探测：用户服务绑定失败（进程未启动或 ${BIND_TIMEOUT_MS}ms 内没连上）"
            )
            return ChannelStatus.Unavailable(method, "Shizuku 用户服务绑定失败（进程未启动或等待超时）")
        }
        return if (call("probe", false) { it.probe() }) {
            pruneStaleServices()
            WriteDiag.detail("shizuku 探测：特权进程内自检通过，通道可用")
            ChannelStatus.Available
        } else {
            WriteDiag.failure(
                "shizuku 探测：用户服务已连接，但特权进程内反射不可用（服务侧自检原文见上一条）"
            )
            ChannelStatus.Unavailable(method, "Shizuku 用户服务已连接，但特权进程内反射不可用")
        }
    }

    override suspend fun getMode(subId: Int): Int =
        call("getCurrentNetworkMode", -1) { it.getCurrentNetworkMode(subId) }

    override suspend fun setMode(subId: Int, mode: NetworkMode): Boolean {
        val startedAt = System.currentTimeMillis()
        val ok = call("setNetworkMode", false) { it.setNetworkMode(subId, mode.value) }
        WriteDiag.detail(
            "shizuku setNetworkMode(subId=$subId, mode=${mode.value}) -> $ok" +
                " 耗时=" + (System.currentTimeMillis() - startedAt) + "ms"
        )
        if (!ok) {
            // 1.5.1：Shizuku 通道的失败原因只存在于用户服务进程内部，应用进程这一侧
            // （用户看到的结论行）本来只能拿到 false。1.5.2 起服务侧的诊断行会随 drainDiag()
            // 回传（见 call()），那句回传的 failure 就是最精确的原因；只有**一条都没拿到**时
            // 才写下面这句兜底，免得把精确原因盖掉。
            val generic = "Shizuku 用户服务里三条 ITelephony 策略都没成功，权威存储也没写进去" +
                "（详细日志模式下可看到服务侧逐条过程）"
            if (WriteDiag.peekFailure().isBlank()) WriteDiag.failure(generic) else WriteDiag.detail(generic)
        }
        return ok
    }

    override suspend fun readAuthStore(subId: Int): AuthStore.Read = AuthStore.decodeRead(
        call(
            "readAuthStore",
            AuthStore.encodeRead(AuthStore.Read.Unavailable("Shizuku 用户服务未绑定")),
        ) { it.readAuthStore(subId) }
    ).also { read ->
        // 详细日志：这一层出错时，应用进程侧只看到「读不到」，无法区分「用户服务没绑上」
        // （binder 根本没发出去）和「provider 拒绝了」（binder 发出去了、返回 DENIED）。
        WriteDiag.detail("shizuku 权威存储读取：subId=$subId -> ${AuthStore.describeRead(read)}")
    }

    override suspend fun writeAuthStore(subId: Int, networkTypes: Long): AuthStore.Write = AuthStore.decodeWrite(
        call(
            "writeAuthStore",
            AuthStore.encodeWrite(AuthStore.Write.Failed("Shizuku 用户服务未绑定")),
        ) { it.writeAuthStore(subId, networkTypes) }
    ).also { write ->
        WriteDiag.detail(
            "shizuku 权威存储写入：subId=$subId 目标=$networkTypes -> ${AuthStore.describeWrite(write)}"
        )
    }

    override suspend fun getDefaultSlot(): Int = call("getDefaultSlot", -1) { it.getDefaultSlot() }

    override suspend fun setDefaultSlot(slot: Int): Boolean =
        call("setDefaultSlot", false) { it.setDefaultSlot(slot) }

    override suspend fun activeSlots(): List<Pair<Int, Int>> = call("activeSlots", emptyList()) { binder ->
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
            WriteDiag.detail("shizuku 已发起权限申请（requestCode=$requestCode）")
        } catch (e: Throwable) {
            Log.e(TAG, "Shizuku.requestPermission($requestCode) failed: ${e.javaClass.simpleName}: ${e.message}")
            WriteDiag.failure(
                "shizuku 权限申请失败 ${e.javaClass.simpleName}: ${e.message}" +
                    "（设备上装的 Shizuku 版本可能偏旧）"
            )
        }
    }

    override fun destroy() {
        WriteDiag.detail("shizuku 通道销毁：请求服务侧解除引用并解绑用户服务")
        try {
            service?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "user service destroy() failed: ${e.javaClass.simpleName}: ${e.message}")
            WriteDiag.detail("shizuku destroy() 失败：${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            Shizuku.unbindUserService(buildUserServiceArgs(), connection, true)
        } catch (e: Throwable) {
            Log.w(TAG, "unbindUserService failed: ${e.javaClass.simpleName}: ${e.message}")
            WriteDiag.detail("shizuku unbindUserService 失败：${e.javaClass.simpleName}: ${e.message}")
        }
        service = null
        serviceBinder = null
    }

    /**
     * 绑定成功后清扫上一次运行遗留的用户服务孤儿进程。
     *
     * 用户服务是 root/shell 权限的独立进程（`<包名>:np_service`），客户端被系统杀掉时
     * 来不及 `unbindUserService(remove = true)`，它就会变成 PPID=1 的孤儿长期驻留，
     * 每个约 40 MB（实测累积到 4 个 / ~180 MB）。本路径每个应用进程只做一次
     * （与 [pruneOrphanedServices] 各用一个标志，见 [pruneStaleOnce] —— 1.5.2 之前两者
     * 共用同一个标志，先到者赢、后到者永久 no-op，见那里的注释）；
     * 清扫只是省内存，失败一律静默，绝不影响通道可用性。
     */
    private suspend fun pruneStaleServices() {
        // 1.5.3：标志改成「真的把清扫命令发出去并得到回答」之后才置位（理由见 [pruneStaleOnce]）。
        // 旧写法先 CAS 再调用，一次超时/绑定失败就把这次机会烧掉，同进程内不会再来第二次。
        if (pruneStaleOnce.get()) return
        val removed = call("pruneStaleProcesses", -1) { it.pruneStaleProcesses() }
        if (removed < 0) return
        pruneStaleOnce.set(true)
        if (removed > 0) Log.i(TAG, "pruned $removed stale Shizuku user service process(es)")
        WriteDiag.detail("shizuku 清扫残留用户服务进程：杀掉 $removed 个")
    }

    /**
     * 应用启动时调用一次：清掉上一次运行遗留的用户服务孤儿进程，清完立刻解绑自己。
     *
     * 为什么不能只靠 [probe] 里那次清扫：`probe()` 只在「真的需要特权通道」时才会被调用，
     * 网络一直健康时 [com.katiusu.netpilot.core.priv.ControlManager.acquire] 根本走不到它 ——
     * 于是每次应用进程被系统杀掉都会留下一个 PPID=1 的 `:np_service`（实测累积到 4 个、
     * 合计约 200 MB，而主进程才 16 MB）。
     *
     * 收尾和清扫本身一样重要：清扫命令只能在用户服务进程里执行，所以这里必须临时绑一个；
     * 清完立刻 [destroy]（unbind remove = true），既不留常驻进程，也不占用
     * [com.katiusu.netpilot.core.priv.ControlManager] 的通道缓存。
     *
     * @return 清掉的孤儿进程数（>= 0，含 0）；**负数表示这次没执行**（Shizuku 未运行 /
     *   没授权 / 绑定失败 / 调用失败），调用方可以在稍后重试 —— 这个「没执行」和「执行了但
     *   一个都没清到」必须分开，否则调用方无法知道该不该重试（1.5.3）。
     */
    suspend fun pruneOrphanedServices(): Int {
        // 1.5.3 修掉的两个缺陷（旧写法：CAS 在最上面，然后才是两道门控）：
        // ① 门控必须在置位之前 —— 启动清扫是在 Application.onCreate 里立刻起线程的，而开机/升级
        //    冷启动那一刻 Shizuku 服务端常常还没起来（或被系统按住），绑定必然失败；旧写法把
        //    「唯一一次机会」烧在那次失败上，本次进程内再也不会重试，于是用户后来正常用起
        //    Shizuku 也清不掉上次遗留的孤儿（实测本机就有一个从开机留到现在、约 59 MB 的
        //    `:np_service`）。
        // ② 标志只在**命令真的发出去并得到回答**之后才置位，返回值区分「没执行」(-1) 与
        //    「执行了、清了 N 个」(>= 0)，好让调用方按需要重试。
        if (pruneOrphanOnce.get()) return PRUNE_DONE
        if (!shizukuAlive()) return PRUNE_NOT_RUN
        if (!ensureServiceBinding()) return PRUNE_NOT_RUN
        return try {
            val removed = call("pruneStaleProcesses", -1) { it.pruneStaleProcesses() }
            if (removed < 0) return PRUNE_NOT_RUN
            pruneOrphanOnce.set(true)
            if (removed > 0) {
                Log.i(TAG, "pruned $removed stale Shizuku user service process(es)")
                // 简要模式下 detail 会被丢掉，而「孤儿到底清没清掉」正是这类改动唯一能自证的东西；
                // 只在真清到东西时用无条件级别，免得每次启动都往日志页刷一行。
                WriteDiag.always("shizuku 启动清扫：回收 $removed 个残留用户服务进程")
            } else {
                WriteDiag.detail("shizuku 启动清扫：没有需要回收的残留进程")
            }
            removed
        } finally {
            // 无论清没清到，都解绑这次临时绑定的用户服务 —— 否则清扫动作本身就变成了新的孤儿。
            destroy()
        }
    }

    private fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 15s 内等到 onServiceConnected（轮询 service 字段，回调在主线程填充）。 */
    private suspend fun ensureServiceBinding(): Boolean {
        serviceBinder?.let { if (it.pingBinder()) return true }
        if (!shizukuAlive()) {
            WriteDiag.always("shizuku 绑定：Shizuku 服务未运行，跳过绑定用户服务")
            return false
        }
        return try {
            val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {
                Shizuku.bindUserService(buildUserServiceArgs(), connection)
                while (service == null) {
                    delay(POLL_INTERVAL_MS)
                }
                true
            } ?: false
            if (bound) {
                WriteDiag.detail("shizuku 绑定：用户服务（:np_service）已连接")
            } else {
                WriteDiag.failure("shizuku 绑定：${BIND_TIMEOUT_MS}ms 内没等到 onServiceConnected")
            }
            bound
        } catch (e: CancellationException) {
            // 1.5.3：协程取消不是「绑定失败」。绑定期被取消（页面离开组合、TileService 被销毁）
            // 旧代码会记成「用户服务未绑定」，把正常取消误报成故障 —— 原样上抛。
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "bindUserService failed: ${e.javaClass.simpleName}: ${e.message}")
            WriteDiag.failure("shizuku 绑定：bindUserService 抛异常 ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 统一的 binder 调用包装。
     *
     * ⚠ 进特权进程的 binder 调用**可能永久挂住**：同步 binder transaction 没有内核级超时，
     * 用户服务里一旦死循环，调用方就再也回不来。这里用 [CALL_TIMEOUT_MS] 包一层只能保证
     * 协程在挂起状态下可被取消；对阻塞在 transact 上的同步调用它无法真正中断，
     * 所以每个调用都必须是幂等/可重入的，超时或真异常一律降级成 [default]，绝不抛给 UI；
     * **但协程取消除外**（1.5.3）：取消要原样上抛，见下面的 catch (e: CancellationException)。
     */
    private suspend fun <T> call(op: String, default: T, block: (IShizukuController) -> T): T {
        if (service == null && !ensureServiceBinding()) {
            Log.w(TAG, "binder call skipped: user service not bound")
            WriteDiag.failure("shizuku $op：用户服务未绑定（bindUserService 失败或等待超时），本次调用没有发出")
            return default
        }
        val controller = service ?: return default
        return try {
            withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(CALL_TIMEOUT_MS) { block(controller) }
                        ?: run {
                            // 超时最常见的原因是同步 binder 调用卡在特权进程里：这条必须留痕。
                            WriteDiag.failure(
                                "shizuku $op：binder 调用超过 ${CALL_TIMEOUT_MS}ms 未返回，" +
                                    "本次结果按失败取值（默认值 $default）"
                            )
                            default
                        }
                } finally {
                    // 成功、超时、异常都要把服务侧攒下的诊断行取回来：那里面才是「为什么失败」的原文
                    // （服务进程没有 LogStore 的 Context，写不进应用进程的日志页）。
                    drainRemoteDiag()
                }
            }
        } catch (e: CancellationException) {
            // 1.5.3 修：协程取消不是「binder 调用失败」。
            // 日志页里那两条 `shizuku getDefaultSlot：binder 调用抛异常 a80/b80:
            // rememberCoroutineScope left the composition` 就是这么来的：调用方是页面/对话框的
            // Composition 作用域，页面离开组合（滑走两页以上、Activity 重建、对话框关闭）时
            // Compose 用 ForgottenCoroutineScopeException（CancellationException 的子类）取消该作用域，
            // 取消在 withContext 的恢复点抛出，被下面的 catch (Throwable) 当故障记了下来 ——
            // 每条 WARN 还会触发一次日志落盘。取消必须原样上抛，否则「真异常」与「正常取消」
            // 混在一起，诊断价值被稀释。
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "binder call failed: ${e.javaClass.simpleName}: ${e.message}")
            WriteDiag.failure("shizuku $op：binder 调用抛异常 ${e.javaClass.simpleName}: ${e.message}")
            default
        }
    }

    /**
     * 取回 Shizuku 用户服务进程攒下的诊断行，并按当前档位写进日志页。
     *
     * 为什么要单独一次 binder 调用：服务进程（`:np_service`）里没有 LogStore 的 Context，
     * `LogStore.log` 只会写进那个进程自己的内存缓冲 —— 谁也读不到。1.5.2 之前，Shizuku 通道
     * 下最关键的逐条失败原因（provider 拒绝、位掩码表外、Context 未就绪…）因此全部丢失。
     *
     * 这一步本身也必须能失败：它用 [DRAIN_TIMEOUT_MS] 兜住，取不到就静默跳过 ——
     * 日志不该反过来把通道搞挂。
     */
    private suspend fun drainRemoteDiag() {
        val controller = service ?: return
        val raw = runCatching {
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) { controller.drainDiag() }
        }.getOrNull()
        WriteDiag.forwardRemote(raw)
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

        /** 取回服务侧诊断行的超时：日志拿不到就跳过，绝不影响通道本身。 */
        const val DRAIN_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 100L

        /** [pruneOrphanedServices] 的「这次没执行」返回值：Shizuku 未运行 / 绑定失败 / 调用失败。 */
        const val PRUNE_NOT_RUN = -1

        /** [pruneOrphanedServices] 的「已经清过、不必再试」返回值。 */
        const val PRUNE_DONE = 0

        /**
         * 孤儿进程清扫的两个入口各用**独立**标志，不能共用。
         *
         * 为什么（1.5.2 修掉的缺陷）：两条路径以前共用同一个 AtomicBoolean，而同进程内
         * CAS 只有先到者能赢 —— 启动清扫在 TemplateApp.onCreate 里立刻起线程，几乎总是
         * 先赢，于是 [pruneStaleServices]（probe 成功后才走的那条）实际从不生效；反过来
         * 若 probe 先赢，启动清扫就直接返回 0，上一次运行遗留的孤儿无人清。
         * 两条路径各自只做一次即可，互不顶掉。
         *
         * 1.5.3 再修：这两个标志的语义统一成「**清扫命令真的成功执行过**才算做过」——
         * 不再用 compareAndSet 抢在调用之前置位。启动那一刻 Shizuku 没起来是常态，
         * 「先置位再失败」会让唯一一次机会被一次注定失败的尝试烧掉，而进程又有
         * MonitorService 的 START_STICKY + 15 分钟心跳长期活着，再也没有第二次 onCreate。
         */
        val pruneStaleOnce = AtomicBoolean(false)
        val pruneOrphanOnce = AtomicBoolean(false)
    }
}
