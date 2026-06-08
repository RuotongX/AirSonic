package com.airsonic.sender.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.api.DeviceType
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** 基于 SSDP 的 DLNA MediaRenderer 发现。 */
class DlnaDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var running = false
    /** location -> 已上报，避免重复解析。 */
    private val seen = ConcurrentHashMap<String, Boolean>()
    @Volatile private var listener: DeviceListener? = null

    private val MCAST = InetAddress.getByName("239.255.255.250")
    private val PORT = 1900
    private val mSearch = (
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        ).toByteArray()

    fun start(l: DeviceListener) {
        listener = l
        acquireLock()
        running = true
        thread(name = "dlna-ssdp", isDaemon = true) { runCatching { loop() }.onFailure { Log.e(TAG, "ssdp loop", it) } }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }; socket = null
        releaseLock()
        seen.clear()
    }

    private fun loop() {
        // reuseAddress 必须在 bind 前设置 → 用未绑定构造再手动 bind。
        val s = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
            soTimeout = 1000
            runCatching { @Suppress("DEPRECATION") joinGroup(MCAST) }
        }
        socket = s
        var ticks = 0
        val buf = ByteArray(8192)
        while (running) {
            // 每 ~5s 主动搜一次（首轮立即）
            if (ticks % 5 == 0) runCatching {
                s.send(DatagramPacket(mSearch, mSearch.size, MCAST, PORT))
            }
            ticks++
            val pkt = DatagramPacket(buf, buf.size)
            val got = runCatching { s.receive(pkt); true }.getOrDefault(false)
            if (!got) continue
            val text = String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1)
            val loc = ssdpLocation(text) ?: continue
            if (seen.putIfAbsent(loc, true) != null) continue
            thread(isDaemon = true) { runCatching { resolveLocation(loc) } }
        }
    }

    /** 防 SSRF：LOCATION 来自未信任的 SSDP 应答，只允许 http(s)+局域网(站点本地)地址。 */
    private fun isLanHttp(raw: String): Boolean = runCatching {
        val u = URL(raw)
        u.protocol.lowercase() in listOf("http", "https") &&
            InetAddress.getByName(u.host).isSiteLocalAddress
    }.getOrDefault(false)

    private fun resolveLocation(loc: String) {
        if (!isLanHttp(loc)) { seen.remove(loc); return }
        val xml = runCatching {
            val conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000
            }
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }.getOrNull() ?: run { seen.remove(loc); return }

        val renderer = parseRenderer(xml, loc) ?: return
        val u = URL(loc)
        val device = AirDevice(
            name = renderer.friendlyName,
            host = u.host,
            port = if (u.port > 0) u.port else 80,
            type = DeviceType.DLNA,
            controlUrl = renderer.avTransportControlUrl,
        )
        listener?.onDeviceFound(device)
    }

    private fun acquireLock() {
        if (multicastLock == null) multicastLock = wifiManager.createMulticastLock(TAG).apply {
            setReferenceCounted(true); acquire()
        }
    }

    private fun releaseLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object { private const val TAG = "DlnaDiscovery" }
}
