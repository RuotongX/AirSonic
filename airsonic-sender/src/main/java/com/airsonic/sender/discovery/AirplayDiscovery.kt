// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceCapabilities
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.api.DeviceType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 基于 Android [NsdManager] 的 AirPlay 设备发现。
 *
 * 解析 `_airplay._tcp` 服务，读取 TXT 记录推断能力位（音频/图片、AirPlay 版本）。
 * 持有 [WifiManager.MulticastLock] 以应对部分机型的省电多播抑制。
 */
class AirplayDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var userListener: DeviceListener? = null
    /** 重启发现的待办标志：true 表示「停止完成后立即重新发现」。 */
    @Volatile private var restartPending = false

    // ---- NsdManager resolve 串行化（关键稳定性修复）----
    // NsdManager 同一时刻只允许一个 resolveService 在飞；并发会 FAILURE_ALREADY_ACTIVE。
    // 原实现失败不重试 → 多台设备同时出现时部分永久解析不出来（设备时隐时现）。
    // 改为单线程队列逐个解析 + 失败重试，根治。
    //
    // 代际隔离：stop→start 快速重启时，旧 worker 可能还阻塞在 poll/await 没退出；
    // 若用共享布尔标志，新 start 把它设回 true 会让旧线程「复活」→ 双 worker 并发 resolve
    // → FAILURE_ALREADY_ACTIVE 回归。每个 worker 绑定自己的代号，代号失效即退出。
    private val resolveQueue = LinkedBlockingQueue<NsdServiceInfo>()
    private val queuedNames = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var resolveWorker: Thread? = null
    private val workerGen = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var activeGen = 0

    /**
     * 启动（或刷新）发现。可重复调用：若已在发现中，则发起一次**干净重启**——
     * 先 `stopServiceDiscovery`，待 [NsdManager.DiscoveryListener.onDiscoveryStopped]
     * 回调真正触发后再 `discoverServices`。
     *
     * 这避免了「异步 stop 未完成就 discoverServices」的竞态：在 vivo 等机型上拆/建快，
     * 直接重启也能拿到设备；但在华为 EMUI 上重启会丢失全部设备（刷新后列表清空再也填不回来）。
     */
    fun start(listener: DeviceListener) {
        userListener = listener
        acquireMulticastLock()

        if (discoveryListener != null) {
            // 已在发现中：请求干净重启，由 onDiscoveryStopped 接力 beginDiscovery()。
            restartPending = true
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            return
        }
        beginDiscovery()
    }

    private fun beginDiscovery() {
        startResolveWorker()
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // 必须清状态：否则 listener 残留，后续 start() 全走「重启」路径
                // 对一个从未 started 的 listener 调 stop → onDiscoveryStopped 永不来 → 发现永久卡死。
                if (discoveryListener === this) discoveryListener = null
                restartPending = false
                userListener?.onDiscoveryFailed("onStartDiscoveryFailed code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "onStopDiscoveryFailed code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
                if (discoveryListener === this) discoveryListener = null
                if (restartPending) {
                    restartPending = false
                    beginDiscovery()
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${serviceInfo.serviceName}")
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // 允许设备再次出现时重新入队解析（EMUI 偶发误丢失）
                queuedNames.remove(serviceInfo.serviceName)
                val device = AirDevice(
                    name = serviceInfo.serviceName,
                    host = serviceInfo.host?.hostAddress ?: "",
                    port = serviceInfo.port
                )
                userListener?.onDeviceLost(device)
            }
        }
        discoveryListener = l
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        restartPending = false
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
        stopResolveWorker()
        releaseMulticastLock()
    }

    /** 入队（去重）。真正的 resolve 由单线程 worker 串行执行，避免 NsdManager 并发限制。 */
    private fun resolve(serviceInfo: NsdServiceInfo) {
        if (!queuedNames.add(serviceInfo.serviceName)) return
        resolveQueue.offer(serviceInfo)
    }

    private fun startResolveWorker() {
        val w = resolveWorker
        if (w != null && w.isAlive && activeGen == workerGen.get()) return   // 当前代 worker 仍在跑
        val gen = workerGen.incrementAndGet()
        activeGen = gen
        resolveWorker = thread(isDaemon = true, name = "airsonic-nsd-resolve-$gen") {
            try {
                while (gen == workerGen.get()) {
                    val info = resolveQueue.poll(1, TimeUnit.SECONDS) ?: continue
                    resolveOneBlocking(info, gen)
                    queuedNames.remove(info.serviceName)   // 解析完成后放行，设备再现可重解析
                }
            } catch (_: InterruptedException) {
                // stop() interrupt：直接退出
            }
        }
    }

    /** 串行解析一个服务，失败重试至多 3 次（含 FAILURE_ALREADY_ACTIVE）；阻塞到回调或超时。 */
    private fun resolveOneBlocking(info: NsdServiceInfo, gen: Int) {
        var attempt = 0
        while (gen == workerGen.get() && attempt < 3) {
            attempt++
            val latch = CountDownLatch(1)
            var ok = false
            try {
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed code=$errorCode name=${si.serviceName} attempt=$attempt")
                        latch.countDown()
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        ok = true
                        runCatching { userListener?.onDeviceFound(toAirDevice(si)) }
                        latch.countDown()
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "resolveService threw: ${t.message}"); latch.countDown()
            }
            if (!latch.await(6, TimeUnit.SECONDS)) Log.w(TAG, "resolve await timeout name=${info.serviceName}")
            if (ok) return
            Thread.sleep(500)   // 退避后重试（覆盖跨代残留的 in-flight resolve 完成窗口）
        }
    }

    private fun stopResolveWorker() {
        workerGen.incrementAndGet()        // 使旧代失效：阻塞中的旧 worker 醒来即自行退出
        resolveWorker?.interrupt()         // 提前打断 poll/await，加速退出
        resolveWorker = null
        resolveQueue.clear()
        queuedNames.clear()
    }

    /**
     * 选出可直连的地址：优先 IPv4，其次非链路本地 IPv6，最后才用链路本地（补 scope id）。
     *
     * 背景：NsdManager resolve 可能只给回 fe80:: 链路本地 IPv6 且不带 scope，
     * 后续 HTTP/配对 connect 直接 EINVAL（compileSdk 33 拿不到 hostAddresses，反射拿）。
     */
    private fun selectAddress(info: NsdServiceInfo): java.net.InetAddress? {
        val candidates = mutableListOf<java.net.InetAddress>()
        // API 34+ 的 getHostAddresses()：运行时反射取，compileSdk 33 也能用
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val list = info.javaClass.getMethod("getHostAddresses").invoke(info)
                as? List<java.net.InetAddress>
            if (list != null) candidates.addAll(list)
        }
        info.host?.let { if (it !in candidates) candidates.add(it) }

        candidates.firstOrNull { it is java.net.Inet4Address }?.let { return it }
        candidates.firstOrNull {
            it is java.net.Inet6Address && !it.isLinkLocalAddress
        }?.let { return it }
        // 只剩链路本地：必须补 scope id，否则 connect EINVAL
        candidates.firstOrNull()?.let { addr ->
            if (addr is java.net.Inet6Address && addr.isLinkLocalAddress &&
                addr.scopedInterface == null && addr.scopeId == 0) {
                val scoped = attachScope(addr)
                if (scoped != null) {
                    Log.i(TAG, "链路本地地址补 scope: ${addr.hostAddress} -> ${scoped.hostAddress}")
                    return scoped
                }
            }
            Log.w(TAG, "no IPv4 candidate for ${info.serviceName}, using ${addr.hostAddress}")
            return addr
        }
        return null
    }

    /** 给链路本地 IPv6 挂上活动网卡 scope（找一张有链路本地地址且在用的接口）。 */
    private fun attachScope(addr: java.net.Inet6Address): java.net.InetAddress? = runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val hasV6 = iface.inetAddresses.toList().any { it is java.net.Inet6Address }
            if (hasV6) {
                return java.net.Inet6Address.getByAddress(null, addr.address, iface)
            }
        }
        null
    }.getOrNull()

    private fun toAirDevice(info: NsdServiceInfo): AirDevice {
        val hostAddr = selectAddress(info)
        val txt = parseTxt(info)
        val features = txt["features"] ?: txt["ft"]
        val model = txt["model"] ?: txt["am"] ?: ""

        val type = when {
            model.startsWith("AppleTV", true) -> DeviceType.APPLE_TV
            model.startsWith("AudioAccessory", true) -> DeviceType.HOMEPOD
            model.startsWith("Mac", true) || model.startsWith("iMac", true) -> DeviceType.MAC
            info.serviceName.contains("Sonos", true) -> DeviceType.SONOS
            info.serviceName.contains("Xiaomi", true) || info.serviceName.contains("小米", true)
                || model.startsWith("L16", true) -> DeviceType.XIAOMI
            else -> DeviceType.UNKNOWN
        }

        val supportsPhoto = type == DeviceType.APPLE_TV || type == DeviceType.MAC
        val supportsVideo = type == DeviceType.APPLE_TV || type == DeviceType.MAC
        // AirPlay 2 设备通常通过 features bit 与 TXT 中的 protovers/srcvers 体现；
        // 这里做保守推断，详细位解析放到 Phase 1。
        val airplayVersion = if (txt.containsKey("features") || txt.containsKey("ft")) 2 else 2

        return AirDevice(
            name = info.serviceName,
            host = hostAddr?.hostAddress ?: "",
            port = info.port,
            type = type,
            capabilities = DeviceCapabilities(
                supportsAudio = true,
                supportsPhoto = supportsPhoto,
                supportsVideo = supportsVideo,
                airplayVersion = airplayVersion,
                requiresEncryptedPairing = airplayVersion == 2
            ),
            txtRecords = txt + mapOf("_features_raw" to (features ?: ""))
        )
    }

    private fun parseTxt(info: NsdServiceInfo): Map<String, String> {
        val out = HashMap<String, String>()
        info.attributes?.forEach { (k, v) ->
            out[k] = v?.let { String(it) } ?: ""
        }
        return out
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock(TAG).apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object {
        private const val TAG = "AirplayDiscovery"
        const val SERVICE_TYPE = "_airplay._tcp."
    }
}