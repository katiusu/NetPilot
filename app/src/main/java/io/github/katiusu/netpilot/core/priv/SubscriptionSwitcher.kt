package io.github.katiusu.netpilot.core.priv

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 切换「默认数据卡」(DDS)。
 *
 * 反射链移植自 TrafficSIM（MIT）的 `PhoneProcessBridge`，去掉了 LSPosed hook 相关逻辑，
 * 改成可在 root 的 `app_process` 或 Shizuku 用户服务里直接调用。
 *
 * 设计要点：
 * - 5 类候选类逐个试，每类内部按参数个数升序试重载，任一成功立即返回；
 * - 实例获取顺序：静态 `getInstance()`（无参 → 带 Context）→ 静态字段 `sInstance`/`INSTANCE`
 *   → `SubscriptionManager.from(Context)`；
 * - 每个候选都 try/catch 吞异常：OEM 改签名、SELinux 拒绝、权限不足都不应该中断整条链；
 * - 反射调用必须 `isAccessible = true`，且**不直接引用任何隐藏类**（只用字符串类名 + 反射）。
 */
object SubscriptionSwitcher {

    private const val TAG = "NetPilot"
    private const val PHONE_PACKAGE = "com.android.phone"

    /** 一些 ROM 只允许特定 caller 包名调用；与 ITelephony 链保持一致。 */
    private const val MAX_PROBED_SLOTS = 4

    /**
     * void 方法的回读校验预算：**读 2 次，中间只等 120ms**（单次调用占用 binder 线程 ≤ 250ms）。
     *
     * 以前是 3 次 × 150ms ≈ 300ms+，而这段等待跑在 Shizuku 用户服务的 binder 线程上，期间
     * 该进程其它 binder 调用（getMode 等）全部排队。切卡本身是异步的，与其在这里死等，
     * 不如把「到底切过去没有」交给下一次采样复核。
     */
    private const val READBACK_ATTEMPTS = 2
    private const val READBACK_INTERVAL_MS = 120L

    /**
     * Shizuku 用户服务有 Context，塞进来后「取活动卡列表」可以走公开 API
     * （`SubscriptionManager.getActiveSubscriptionInfoList()`）；
     * app_process 的 root 入口没有 Context，则自动退化为静态槽位扫描。
     */
    @Volatile
    private var appContext: Context? = null

    fun attachContext(context: Context?) {
        appContext = context?.applicationContext ?: context
    }

    /** 5 类候选：类名 → 该方法名集合（按契约顺序，不重排）。 */
    private val CANDIDATES: List<Pair<String, List<String>>> = listOf(
        "com.android.internal.telephony.SubscriptionController" to
            listOf("setDefaultDataSubId", "setDefaultDataSubIdWithReason"),
        "com.android.internal.telephony.SubscriptionManagerService" to
            listOf("setDefaultDataSubId", "setDefaultDataSubIdWithReason"),
        "com.android.internal.telephony.subscription.SubscriptionManagerService" to
            listOf("setDefaultDataSubId", "setDefaultDataSubIdWithReason"),
        "android.telephony.SubscriptionManager" to
            listOf("setDefaultDataSubId"),
        "com.android.internal.telephony.PhoneSwitcher" to
            listOf("setPreferredDataSubscriptionId")
    )

    /**
     * 设置默认数据卡，**并通过回读确认真的切过去了**。
     *
     * 以前只要反射调用没抛异常就返回 true（`result !is Boolean || result`），于是
     * `setPreferredDataSubscriptionId` 这类 void 方法一律算成功 —— 服务侧因为无权限 / 无卡 /
     * subId 非法而静默 no-op 时，界面照样报「已切换」。现在：Boolean 方法以返回值为准，
     * void（返回值不是 Boolean）必须回读 `getDefaultDataSubscriptionId()` 校验。
     *
     * @return true 仅当默认数据卡确实已经是 [subId]。
     */
    fun setDefaultDataSubId(subId: Int): Boolean {
        if (subId < 0) return false
        TelephonyReflection.ensureHiddenApiExempted()

        // 已经是目标卡就别再下发：服务侧会把它当成「无变化」直接 no-op，
        // 而下面 void 方法的回读校验会把这种 no-op 误判成失败。
        val before = currentDefaultDataSubId()
        if (before == subId) {
            Log.i(TAG, "setDefaultDataSubId($subId): 已经是默认数据卡，跳过调用")
            return true
        }

        var called = false
        for ((className, methodNames) in CANDIDATES) {
            val clazz = loadClass(className) ?: continue
            val instance = obtainInstance(clazz)
            val methods = (clazz.declaredMethods.toList() + clazz.methods.toList())
                .filter { it.name in methodNames }
                .sortedBy { it.parameterTypes.size }

            for (method in methods) {
                val isStatic = Modifier.isStatic(method.modifiers)
                // 实例方法但拿不到实例：跳过（invoke(null, ...) 只会得到 NPE）
                if (!isStatic && instance == null) continue
                val args = buildArgs(method, subId) ?: continue
                try {
                    method.isAccessible = true
                    val result = method.invoke(if (isStatic) null else instance, *args)
                    if (result is Boolean) {
                        // Boolean 方法自己说了算：false 就是没切开。
                        if (result) {
                            Log.i(TAG, "setDefaultDataSubId($subId) via $className#${method.name} ok（Boolean=true）")
                            return true
                        }
                        Log.w(TAG, "setDefaultDataSubId($subId) via $className#${method.name} returned false")
                    } else {
                        // void：返回值不携带结果，只能回读。没生效就继续试下一个候选。
                        called = true
                        if (awaitDefaultDataSubId(subId)) {
                            Log.i(TAG, "setDefaultDataSubId($subId) via $className#${method.name} ok（void，回读已生效）")
                            return true
                        }
                        Log.w(
                            TAG,
                            "setDefaultDataSubId($subId) via $className#${method.name} 调用未抛异常但回读仍是 " +
                                "${currentDefaultDataSubId()}，继续尝试下一个候选"
                        )
                    }
                } catch (e: Throwable) {
                    Log.w(
                        TAG,
                        "setDefaultDataSubId($subId) via $className#${method.name} failed: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
        }

        Log.w(
            TAG,
            "setDefaultDataSubId($subId): all candidates failed（调用了 void 候选：$called，" +
                "当前默认 ${currentDefaultDataSubId()}）"
        )
        return false
    }

    /** 当前默认数据 subId；拿不到返回 -1。公开静态 API，app_process 里没有 Context 也能调。 */
    fun currentDefaultDataSubId(): Int = try {
        SubscriptionManager.getDefaultDataSubscriptionId()
    } catch (e: Throwable) {
        Log.w(TAG, "getDefaultDataSubscriptionId failed: ${e.javaClass.simpleName}: ${e.message}")
        -1
    }

    /**
     * 回读校验：立刻读一次，再等 120ms 读一次，然后收工。
     *
     * 为什么只等这么短：PhoneSwitcher 切换是异步的，但本方法是在 Shizuku 用户服务的 binder
     * 线程上跑的，等久了会把该进程其它 binder 调用全堵住。读到旧值就报失败、由下一轮采样复核，
     * 比让调用方阻塞更划算。
     */
    private fun awaitDefaultDataSubId(subId: Int): Boolean {
        repeat(READBACK_ATTEMPTS) { attempt ->
            if (currentDefaultDataSubId() == subId) return true
            if (attempt < READBACK_ATTEMPTS - 1) {
                try {
                    Thread.sleep(READBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    /** 遍历活动卡列表找 `simSlotIndex == slot` 的 subId，失败返回 -1。 */
    fun resolveSubIdBySlot(slot: Int): Int {
        if (slot < 0) return -1
        return activeSlotList().firstOrNull { it.first == slot }?.second ?: -1
    }

    /**
     * 活动卡列表 `(slot, subId)`，失败返回空列表。
     *
     * 有 Context 时优先走公开 API（`activeSubscriptionInfoList`，需要 READ_PHONE_STATE，
     * Shizuku 用户服务/root 都有）；否则用 `SubscriptionManager.getSubscriptionId(slot)`
     * 静态扫描 0..3 号槽位 —— 这两个静态方法都是公开 API，app_process 里不需要 Context。
     */
    fun activeSlotList(): List<Pair<Int, Int>> {
        appContext?.let { ctx ->
            try {
                val list = ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
                if (!list.isNullOrEmpty()) {
                    return list
                        .map { it.simSlotIndex to it.subscriptionId }
                        .filter { it.first >= 0 && it.second >= 0 }
                        .distinct()
                        .sortedBy { it.first }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "activeSlotList via SubscriptionManager failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return slotScan()
    }

    /** 无 Context 路径：静态扫描槽位。 */
    private fun slotScan(): List<Pair<Int, Int>> = try {
        TelephonyReflection.ensureHiddenApiExempted()
        val result = ArrayList<Pair<Int, Int>>(2)
        for (slot in 0 until MAX_PROBED_SLOTS) {
            val subId = try {
                SubscriptionManager.getSubscriptionId(slot)
            } catch (_: Throwable) {
                -1
            }
            if (subId >= 0 && SubscriptionManager.isUsableSubscriptionId(subId)) {
                result.add(slot to subId)
            }
        }
        result.sortedBy { it.first }
    } catch (e: Throwable) {
        Log.w(TAG, "slotScan failed: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

    // ---------------------------------------------------------------- 私有工具

    private fun loadClass(name: String): Class<*>? {
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            SubscriptionSwitcher::class.java.classLoader,
            ClassLoader.getSystemClassLoader()
        )
        for (loader in loaders) {
            try {
                return Class.forName(name, false, loader)
            } catch (_: Throwable) {
                // 换下一个 ClassLoader（framework 类要走 boot classloader 委派）
            }
        }
        return try {
            Class.forName(name)
        } catch (_: Throwable) {
            Log.w(TAG, "class not found: $name")
            null
        }
    }

    private fun obtainInstance(clazz: Class<*>): Any? {
        // 1) 静态 getInstance(...)：无参优先，其次带 Context
        for (name in listOf("getInstance", "getInstanceInternal")) {
            val candidates = clazz.declaredMethods
                .filter { it.name == name && Modifier.isStatic(it.modifiers) }
                .sortedBy { it.parameterTypes.size }
            for (method in candidates) {
                val parameterTypes = method.parameterTypes
                if (parameterTypes.size > 1) continue
                if (parameterTypes.size == 1 &&
                    (parameterTypes[0] != Context::class.java || appContext == null)
                ) {
                    continue
                }
                val args: Array<Any?> =
                    if (parameterTypes.isEmpty()) emptyArray() else arrayOf<Any?>(appContext)
                try {
                    method.isAccessible = true
                    method.invoke(null, *args)?.let { return it }
                } catch (_: Throwable) {
                    // 换下一个重载
                }
            }
        }

        // 2) 静态字段 sInstance / INSTANCE
        for (fieldName in listOf("sInstance", "INSTANCE")) {
            try {
                val field = clazz.getDeclaredField(fieldName).apply { isAccessible = true }
                if (Modifier.isStatic(field.modifiers)) {
                    field.get(null)?.let { return it }
                }
            } catch (_: Throwable) {
                // 字段不存在，继续
            }
        }

        // 3) SubscriptionManager 的公开入口 from(Context)
        appContext?.let { ctx ->
            try {
                clazz.getMethod("from", Context::class.java).invoke(null, ctx)?.let { return it }
            } catch (_: Throwable) {
                // 该类没有 from(Context)
            }
        }
        return null
    }

    /**
     * 按契约给反射方法造参数：int→subId、boolean→false、long→0L、
     * String→"com.android.phone"、Context→已缓存的 application context；
     * 其它原始类型/无法确定的引用类型直接放弃该方法（返回 null）。
     */
    private fun buildArgs(method: Method, subId: Int): Array<Any?>? {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any?>(types.size)
        for (i in types.indices) {
            val type = types[i]
            args[i] = when {
                type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java -> subId
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> false
                type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> 0L
                type == String::class.java -> PHONE_PACKAGE
                type == Context::class.java -> appContext
                type.isPrimitive -> return null
                else -> return null
            }
        }
        return args
    }
}
