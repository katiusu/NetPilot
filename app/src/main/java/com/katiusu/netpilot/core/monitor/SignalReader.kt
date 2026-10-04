package com.katiusu.netpilot.core.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * 采集信号、网络制式与延迟。
 *
 * 全部读取都包在 `runCatching` 里：本应用可能没拿到 READ_PHONE_STATE，或者当前
 * Android 用户下没有插卡，这些都不该让监控线程崩掉，一律降级成 `null`（未知）。
 *
 * 注意「未知」与「0」的区别贯穿整个降级判定：读不到 RSRP 时**不能**当成强信号。
 */
object SignalReader {

    /** 默认数据卡的 subId；失败或未插卡返回 -1。 */
    fun defaultDataSubId(): Int = runCatching {
        // 需要 READ_PHONE_STATE 才返回真实值，否则返回默认卡的 subId 或 -1。
        SubscriptionManager.getDefaultDataSubscriptionId()
    }.getOrDefault(-1)

    /** subId → 逻辑槽位（0 = SIM1，1 = SIM2）；失败返回 -1。 */
    fun slotOfSubId(subId: Int): Int = runCatching {
        SubscriptionManager.getSlotIndex(subId)
    }.getOrDefault(-1)

    /**
     * 制式值 → 可读名。
     *
     * 不用 `TelephonyManager.getNetworkTypeName(int)`：那是 @hide 的静态方法，
     * 公开 SDK 里取不到（编译期 Unresolved reference）。
     */
    // NETWORK_TYPE_{EVDO,CDMA,IDEN,1xRTT,...} 在 Java 侧被标了 @Deprecated，但 2G/3G 的
    // 判定仍然要用它们的常量值；这里只读常量、不做任何废弃 API 的替代，直接抑制告警。
    @Suppress("DEPRECATION")
    fun networkTypeName(raw: Int): String = when (raw) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        // 19 = NETWORK_TYPE_LTE_CA，常量是 @hide，用字面量
        19 -> "4G+ LTE-CA"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_1xRTT -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "VoWiFi"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "未知"
        else -> "未知($raw)"
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun read(
        context: Context,
        subId: Int,
        pingTarget: String,
        pingTimeoutMs: Int,
    ): SignalSnapshot {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = runCatching { manager?.activeNetwork }.getOrNull()
        val capabilities = runCatching {
            if (manager == null || activeNetwork == null) null
            else manager.getNetworkCapabilities(activeNetwork)
        }.getOrNull()
        val onWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        var ssid: String? = null
        var bssid: String? = null
        if (onWifi) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val info = runCatching { wifi?.connectionInfo }.getOrNull()
            ssid = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
            bssid = info?.bssid?.takeIf { it.isNotEmpty() && it != "02:00:00:00:00:00" }
        }

        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val scoped = runCatching {
            if (telephony == null) null
            else if (subId >= 0) telephony.createForSubscriptionId(subId) else telephony
        }.getOrNull()

        val rawType = runCatching { scoped?.dataNetworkType ?: 0 }.getOrDefault(0)
        val operatorName = runCatching { scoped?.networkOperatorName.orEmpty() }.getOrDefault("")

        // 只读一次 signalStrength：没有 READ_PHONE_STATE 时它会抛 SecurityException，
        // 或直接返回 null，两种都算「读不到」，绝不因此崩掉监控线程。
        val strength = runCatching { scoped?.signalStrength }.getOrNull()
        val strengthList: List<CellSignalStrength> =
            runCatching { strength?.cellSignalStrengths }.getOrNull().orEmpty()

        var rsrp: Int? = null
        var sinr: Int? = null
        var sinrSource: String? = null
        strengthList.forEach { item ->
            when (item) {
                is CellSignalStrengthNr -> {
                    if (rsrp == null) rsrp = item.ssRsrp.asRsrp()
                    // SINR 来源 1：NR 的 ssSinr，单位直接是 dB。
                    // 读不到就是真的读不到，不做估算：SINR 估错会直接改变网络质量判定结果。
                    if (sinr == null) {
                        sinr = item.ssSinr.asSinr()
                        if (sinr != null) sinrSource = SINR_SOURCE_NR_SS
                    }
                }
                is CellSignalStrengthLte -> {
                    if (rsrp == null) rsrp = item.rsrp.asRsrp()
                    // SINR 来源 1：LTE 没有 ssSinr（那是 NR 才有的字段），唯一来源是 rssnr；
                    // rssnr 的单位是 **0.1 dB**，直接当 dB 用会得到 10 倍大的假读数
                    // （例如真实的 13 dB 会显示成 130），这里必须换算。
                    if (sinr == null) {
                        sinr = item.rssnr.asTenthDbSinr()
                        if (sinr != null) sinrSource = SINR_SOURCE_LTE_RSSNR
                    }
                }
                else -> Unit
            }
        }
        if (rsrp == null) {
            // 兜底：某些机型不填具体制式的强度，用通用 dBm 近似。
            rsrp = runCatching { strengthList.firstOrNull()?.dbm }.getOrNull()?.asRsrp()
        }

        // SINR 多源回退：来源 1（signalStrength.cellSignalStrengths）拿不到时，再从
        // allCellInfo 取一次。这条路要 READ_PHONE_STATE + ACCESS_FINE_LOCATION，
        // 且 Android 12+ 还要求系统定位服务开着，所以下面每一步失败都记成一条
        // **具体**原因，而不是只给「未知」。
        val hasPhoneState = hasPermission(context, Manifest.permission.READ_PHONE_STATE)
        val hasFineLocation = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val locationServiceOn = locationEnabled(context)
        if (sinr == null && hasPhoneState && hasFineLocation && locationServiceOn == true) {
            val cells = runCatching { scoped?.allCellInfo }.getOrNull().orEmpty()
            // 优先已注册（当前驻留）的小区：邻区的 SINR 代表不了用户此刻的体验。
            for (cell in cells.sortedByDescending { it.isRegistered }) {
                when (val item = runCatching { cell.cellSignalStrength }.getOrNull()) {
                    is CellSignalStrengthNr -> {
                        val value = item.ssSinr.asSinr()
                        if (value != null) {
                            sinr = value
                            sinrSource = SINR_SOURCE_CELL_NR_SS
                            break
                        }
                    }
                    is CellSignalStrengthLte -> {
                        val value = item.rssnr.asTenthDbSinr()
                        if (value != null) {
                            sinr = value
                            sinrSource = SINR_SOURCE_CELL_LTE_RSSNR
                            break
                        }
                    }
                    else -> Unit
                }
            }
        }

        val sinrReasonKind = if (sinr == null) {
            sinrUnavailableReason(
                hasPhoneState = hasPhoneState,
                hasFineLocation = hasFineLocation,
                locationEnabled = locationServiceOn,
                rawNetworkType = rawType,
                sawNr = strengthList.any { it is CellSignalStrengthNr },
                sawLte = strengthList.any { it is CellSignalStrengthLte },
            )
        } else {
            null
        }

        // 无论是否在 Wi-Fi 上都测量延迟：界面要能回答「到底通不通」，
        // 至于要不要据此判定网络质量差，交给 FakeSignalDetector 决定。
        val ping = ping(pingTarget, pingTimeoutMs)
        val displayType = if (onWifi) "WLAN" else networkTypeName(rawType)

        return SignalSnapshot(
            timeMs = System.currentTimeMillis(),
            subId = subId,
            slot = slotOfSubId(subId),
            networkType = displayType,
            rawNetworkType = rawType,
            operatorName = operatorName,
            rsrp = rsrp,
            sinr = sinr,
            sinrSource = sinrSource,
            sinrReasonKind = sinrReasonKind,
            pingMs = ping.ms,
            pingTarget = ping.target,
            pingError = ping.error,
            isWifi = onWifi,
            wifiSsid = ssid,
            wifiBssid = bssid,
        )
    }

    /** 一次延迟探测的结果；[error] 只在 [ms] 为 null 时非空。 */
    data class PingResult(val ms: Int?, val error: String?, val target: String)

    /**
     * 依次尝试主目标与备用目标，返回第一个成功的往返毫秒数。
     *
     * 探测的是 HTTP **首字节时间**，而不是 TCP 握手。国内网络里裸 IP + 固定端口的
     * 探测极易整片失效：`114.114.114.114:53` 的 TCP 53 常被运营商拦截，
     * `223.5.5.5:443` / `119.29.29.29:443` 是 DoT 端口同样常被过滤，`8.8.8.8` 在国内
     * 基本被黑洞丢弃 —— 四个目标全灭时界面只会长期显示「Ping 失败」，而且还占满
     * 4 × timeoutMs。HTTP 校验地址反映的才是「用户到底能不能上网」。
     *
     * 全部失败时回传**最后一个**目标与精确原因（异常类名 + message，例如
     * `java.net.SocketTimeoutException: connect timed out`），用户才能在界面上定位。
     */
    fun ping(pingTarget: String, timeoutMs: Int): PingResult {
        val requested = pingTarget.trim().ifEmpty { DEFAULT_PING_TARGET }
        val timeout = timeoutMs.coerceAtLeast(1)
        // 去重后按「配置的主目标 → 内置优先级」排列，顺序即优先级（同 URL 不会探两次）。
        val candidates = LinkedHashSet<String>(PING_TARGETS.size + 1).apply {
            add(requested)
            addAll(PING_TARGETS)
        }.take(MAX_PING_ATTEMPTS)
        val totalBudgetMs = timeout.toLong() * TOTAL_PING_BUDGET_FACTOR
        val startedAt = System.nanoTime()
        var lastTarget = requested
        var lastError = "无可用探测目标"
        for (target in candidates) {
            lastTarget = target
            val remainingMs = totalBudgetMs - (System.nanoTime() - startedAt) / 1_000_000L
            if (remainingMs <= 0L) {
                lastError = "总预算 ${totalBudgetMs}ms 已耗尽，未尝试 $target"
                break
            }
            // 单次超时不超过剩余预算，整轮耗时的上限才是真的（不会多目标叠加）。
            val attemptTimeoutMs = minOf(timeout.toLong(), remainingMs).toInt()
            try {
                // 只要拿到状态行就算连通（4xx/5xx 也有真实 RTT），首字节耗时即延迟。
                return PingResult(httpProbe(target, attemptTimeoutMs), null, target)
            } catch (t: Throwable) {
                lastError = "$target 失败：${t.describe()}"
            }
        }
        return PingResult(null, lastError, lastTarget)
    }

    /**
     * HTTP 探测：计时从发起前到状态行返回，也就是首字节耗时。
     *
     * 不读 body：`generate_204` 本来就没有 body，读它只会把 readTimeout 再叠一次。
     * 不跟随重定向、不缓存：两者都会凭空增加往返或命中本地缓存，让读数失真。
     * 异常向上抛，由 [ping] 记录精确原因（超时、DNS 失败、连接被拒等）。
     */
    private fun httpProbe(url: String, timeoutMs: Int): Int {
        val startedAt = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            // 状态行回来就算连通，不读 body。
            connection.responseCode
            return ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
        } finally {
            connection?.disconnect()
        }
    }

    /** 主探测目标，与 [DowngradeThresholds.pingTarget] 的默认值同源。 */
    const val DEFAULT_PING_TARGET = "http://www.bing.com/"

    /**
     * 探测目标，顺序即优先级。
     *
     * 首选必应：国内可直连、响应稳定，且它有固定的 30x 跳转 —— 本实现只取状态行、
     * 不跟随重定向，所以一次请求就拿到真实 RTT。`cn.bing.com` 是同一家的备用入口。
     * 其后是百度（国内最稳的大站之一）与厂商联网校验地址（系统自己就在轮询它）。
     * Google 的校验地址在多数国内网络会被丢弃，已从列表移除。
     */
    private val PING_TARGETS = listOf(
        DEFAULT_PING_TARGET,
        "http://cn.bing.com/",
        "http://www.baidu.com/",
        "http://connectivitycheck.platform.hicloud.com/generate_204",
    )

    /** 单轮最多尝试几个目标；等于 [PING_TARGETS] 的条数，保证每个备用目标都轮得到。 */
    private const val MAX_PING_ATTEMPTS = 4

    /**
     * 整轮探测的总预算 = timeoutMs 的倍数。
     *
     * 取 3 而不是「尝试数 × 超时」：目标如果快速失败（DNS 立刻报错、连接被拒），
     * 剩余预算足够继续试下一个；只有真在超时时才会提前收手，把最坏耗时压在
     * 3 × timeoutMs 以内 —— 原实现最坏要阻塞 4 × timeoutMs（默认 10 秒）。
     */
    private const val TOTAL_PING_BUDGET_FACTOR = 3L

    /** SINR 来源标识；界面直接显示，用户才能对比不同来源的读数差在哪。 */
    const val SINR_SOURCE_NR_SS = "NR ssSinr"
    const val SINR_SOURCE_LTE_RSSNR = "LTE rssnr"
    const val SINR_SOURCE_CELL_NR_SS = "CellInfo NR ssSinr"
    const val SINR_SOURCE_CELL_LTE_RSSNR = "CellInfo LTE rssnr"

    /** 是否持有某个运行时权限；读不到状态一律按「没有」处理，宁可多给一句原因。 */
    private fun hasPermission(context: Context, permission: String): Boolean = runCatching {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 系统定位服务是否开启；`null` 表示读不到（取不到 LocationManager 时）。 */
    private fun locationEnabled(context: Context): Boolean? = runCatching {
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled
    }.getOrNull()

    /**
     * 一个 SINR 来源都没取到时，给出**具体**原因。
     *
     * 判定顺序就是「用户还能做什么」的优先级：
     * 1. 没权限 —— 去授权就能解决，永远先说；
     * 2. 根本没驻留蜂窝 —— 没卡 / 没信号，说别的都是误导；
     * 3. 已经看到 NR / LTE 小区，但它的 SINR 字段是 UNAVAILABLE —— 这是**确定**的答案：
     *    allCellInfo 读同一个小区拿到的是同一个字段，再去怪权限就是错的；
     * 4. 连小区对象都没看到过 —— 这时才轮到定位权限 / 定位开关，因为那确实挡住了
     *    allCellInfo 这条本可能救回来的路。
     */
    private fun sinrUnavailableReason(
        hasPhoneState: Boolean,
        hasFineLocation: Boolean,
        locationEnabled: Boolean?,
        rawNetworkType: Int,
        sawNr: Boolean,
        sawLte: Boolean,
    ): SinrUnavailableReason {
        if (!hasPhoneState) return SinrUnavailableReason.MISSING_PHONE_STATE
        if (rawNetworkType == 0) return SinrUnavailableReason.NO_CELLULAR
        // NR 优先：NSA/SA 下 ssSinr 才是最贴近 5G 体验的质量读数。
        if (sawNr || rawNetworkType == TelephonyManager.NETWORK_TYPE_NR) {
            return SinrUnavailableReason.NR_SS_SINR_UNAVAILABLE
        }
        if (sawLte || rawNetworkType == TelephonyManager.NETWORK_TYPE_LTE) {
            return SinrUnavailableReason.LTE_RSSNR_UNAVAILABLE
        }
        if (!hasFineLocation) return SinrUnavailableReason.MISSING_FINE_LOCATION
        if (locationEnabled != true) return SinrUnavailableReason.LOCATION_SERVICES_OFF
        return SinrUnavailableReason.NOT_REPORTED
    }

    /**
     * 异常 → 一行诊断文本：**异常类名 + message 都要在**。
     *
     * 只留 message 会丢掉类型（`CLEARTEXT communication … not permitted` 到底是谁抛的？），
     * 只留类名用户又不知道错在哪；message 为空时补一句，避免界面出现「xxx: 」这种残句。
     * 明文流量被系统拦截时额外点明原因，否则用户只能看到一句英文异常。
     */
    private fun Throwable.describe(): String {
        val message = message?.trim().orEmpty()
        val base = if (message.isEmpty()) {
            "${javaClass.name}（无异常消息）"
        } else {
            "${javaClass.name}: $message"
        }
        val cleartext = javaClass.name == "java.net.UnknownServiceException" &&
            message.contains("CLEARTEXT")
        return if (cleartext) {
            "$base → 系统禁止明文 HTTP，需要放行该域名的明文流量或改用 HTTPS 探测目标"
        } else {
            base
        }
    }

    /** RSRP 合法区间；`CellInfo.UNAVAILABLE`(Int.MAX_VALUE) 与越界值都算读不到。 */
    private fun Int.asRsrp(): Int? = takeIf { it != Int.MAX_VALUE && it in -140..-25 }

    /** SINR 合法区间，单位 dB。0 dB 是合法读数，不能像 RSRP 那样排除 0。 */
    private fun Int.asSinr(): Int? = takeIf { it != Int.MAX_VALUE && it in -30..40 }

    /** LTE 的 `rssnr` 单位是 0.1 dB，换算成整数 dB（13 dB 会存成 130）。 */
    private fun Int.asTenthDbSinr(): Int? =
        takeIf { it != Int.MAX_VALUE && it in -300..400 }?.let { Math.round(it / 10f) }
}
