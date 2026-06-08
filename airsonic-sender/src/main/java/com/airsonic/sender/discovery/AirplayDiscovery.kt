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

    private val resolving = ConcurrentHashMap<String, Boolean>()

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
        resolving.clear()
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
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
        releaseMulticastLock()
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        val key = serviceInfo.serviceName
        if (resolving.putIfAbsent(key, true) != null) return

        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving.remove(key)
                Log.w(TAG, "resolve failed code=$errorCode name=${serviceInfo.serviceName}")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolving.remove(key)
                val device = toAirDevice(serviceInfo)
                userListener?.onDeviceFound(device)
            }
        })
    }

    private fun toAirDevice(info: NsdServiceInfo): AirDevice {
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
            host = info.host?.hostAddress ?: "",
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
