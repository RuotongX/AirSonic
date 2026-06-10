// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

/**
 * 探测某主机是否为 Sonos：GET http://{host}:1400/xml/device_description.xml。
 * 是 → 返回固定的 AVTransport 控制 URL；否 / 不可达 → null。
 */
fun probeSonos(
    host: String,
    connectTimeoutMs: Int = 3000,
    readTimeoutMs: Int = 5000,
): String? = probeSonosWithReason(host, connectTimeoutMs, readTimeoutMs).first

/**
 * 探测结果 + 失败原因（诊断用）。reason ∈
 *   ok / empty / ipv6 / pin-reject / not-sonos / http-<code> / err:<异常名>。
 * 走 pinLanUrl（仅 RFC1918 IPv4、钉死 IP）+ 限读 512KB；带 User-Agent（部分 UPnP 实现对空 UA 敏感）。
 */
fun probeSonosWithReason(
    host: String,
    connectTimeoutMs: Int = 3000,
    readTimeoutMs: Int = 5000,
): Pair<String?, String> {
    if (host.isEmpty()) return null to "empty"
    if (host.contains(':')) return null to "ipv6"               // IPv6：pinLanUrl 只放行 IPv4，单独标出
    val pin = pinLanUrl("http://$host:1400/xml/device_description.xml")
        ?: return null to "pin-reject"                          // 非内网 IPv4 / 解析失败
    return try {
        val conn = (pin.url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false                     // 安全：不自动跟随（防 SSRF）；3xx 会经诊断报 http-<code>
            setRequestProperty("Host", pin.hostHeader)
            setRequestProperty("User-Agent", "AirSonic/1.0")
        }
        try {
            val code = conn.responseCode                        // 触发连接；失败抛异常进 catch
            if (code !in 200..299) return null to "http-$code"  // 区分重定向/拒绝等
            val body = conn.inputStream.use { ins ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
                while (true) { val n = ins.read(buf); if (n < 0) break; total += n; if (total > 512 * 1024) break; out.write(buf, 0, n) }
                String(out.toByteArray(), Charsets.UTF_8)
            }
            if (isSonosDescription(body)) sonosControlUrl(host) to "ok" else null to "not-sonos"
        } finally { conn.disconnect() }
    } catch (t: Throwable) {
        null to "err:${t.javaClass.simpleName}"                 // SocketTimeout/Connect/UnknownHost 等
    }
}
