// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.api.DeviceType
import com.airsonic.sender.dlna.fetchLanText
import java.net.DatagramPacket
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
        "MX: 1\r\n" +                  // 设备随机延迟 0..MX 秒应答；2→1 直接砍半首响应等待
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
            // SSDP 走 UDP 会丢包：首 3 秒每秒补发一发（标准做法是重复 M-SEARCH），之后每 ~5s 一发。
            // 丢一发不再要等 5s 下一轮——这是「DLNA 扫描慢」的主因之一。
            if (ticks < 3 || ticks % 5 == 0) runCatching {
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

    private fun resolveLocation(loc: String) {
        // LOCATION 来自未信任的 SSDP 应答 → 经 fetchLanText 钉定局域网 IP + 限读，防 SSRF/rebinding/DoS。
        val xml = fetchLanText(loc) ?: run { seen.remove(loc); return }

        val renderer = parseRenderer(xml, loc) ?: return
        val u = URL(loc)
        val device = AirDevice(
            name = renderer.friendlyName,
            host = u.host,
            port = if (u.port > 0) u.port else 80,
            type = DeviceType.DLNA,
            controlUrl = renderer.avTransportControlUrl,
            renderingControlUrl = renderer.renderingControlUrl,
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