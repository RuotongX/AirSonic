// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

/**
 * 局域网 HTTP 安全访问（DLNA 用）。
 *
 * controlUrl / LOCATION 等地址来自 SSDP 发现的设备描述——**未信任源**。恶意设备可借此
 * 做 SSRF / DNS rebinding（验证时给局域网 IP、连接时换成公网/元数据 IP）/ 超大响应 DoS。
 * 这里统一防护：
 *  - 仅 http/https，禁止 userinfo
 *  - 主机只解析一次 → 必须 IPv4 站点本地(RFC1918)，排除回环/链路本地/任意/多播
 *  - 用解析得到的 IP 字面量重建 URL（连接时不再二次 DNS → 钉死，杜绝 rebinding），Host 头保留原主机
 *  - 读取设限，超过上限即放弃
 */

/** 单次 LAN 抓取默认上限：UPnP 设备描述很小。 */
const val MAX_LAN_FETCH_BYTES = 512 * 1024

/** 钉定到校验过的局域网 IP 的 URL + 原始 Host 头。 */
data class PinnedUrl(val url: URL, val hostHeader: String)

/** 校验并钉定一个局域网 http(s) URL；非法（非内网/非 http/带 userinfo/解析失败）返回 null。 */
fun pinLanUrl(raw: String): PinnedUrl? = runCatching {
    val u = URL(raw)
    if (u.protocol.lowercase() !in listOf("http", "https")) return@runCatching null
    if (u.userInfo != null) return@runCatching null
    val addr = InetAddress.getByName(u.host)
    val ok = addr is Inet4Address && addr.isSiteLocalAddress &&
        !addr.isLoopbackAddress && !addr.isLinkLocalAddress &&
        !addr.isAnyLocalAddress && !addr.isMulticastAddress
    if (!ok) return@runCatching null
    val pinned = URL(u.protocol, addr.hostAddress, u.port, u.file)
    val hostHeader = if (u.port > 0) "${u.host}:${u.port}" else u.host
    PinnedUrl(pinned, hostHeader)
}.getOrNull()

/** 对校验+钉定后的 LAN URL 发 GET，读取上限 [maxBytes]；非法/超限/失败返回 null。 */
fun fetchLanText(
    raw: String,
    maxBytes: Int = MAX_LAN_FETCH_BYTES,
    connectTimeoutMs: Int = 3000,
    readTimeoutMs: Int = 3000,
): String? {
    val pin = pinLanUrl(raw) ?: return null
    return runCatching {
        val conn = (pin.url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Host", pin.hostHeader)
        }
        try {
            if (conn.contentLengthLong > maxBytes) return@runCatching null
            conn.inputStream.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return@runCatching null
                    out.write(buf, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}