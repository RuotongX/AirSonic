// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

/**
 * 探测某主机是否为 Sonos：GET http://{host}:1400/xml/device_description.xml。
 * 是 → 返回固定的 AVTransport 控制 URL；否 / 不可达 → null。
 * 复用 fetchLanText（仅 RFC1918、钉死 IP 防 DNS rebinding、限读，返回值可空）。
 */
fun probeSonos(
    host: String,
    connectTimeoutMs: Int = 3000,
    readTimeoutMs: Int = 3000,
): String? = probeSonosWithReason(host, connectTimeoutMs, readTimeoutMs).first

/** 探测结果 + 失败原因（诊断用）。reason ∈ ok/empty/ipv6/pin-reject/unreachable/not-sonos。 */
fun probeSonosWithReason(
    host: String,
    connectTimeoutMs: Int = 3000,
    readTimeoutMs: Int = 3000,
): Pair<String?, String> {
    if (host.isEmpty()) return null to "empty"
    // IPv6 一眼可辨（含冒号）：pinLanUrl 只放行 IPv4 RFC1918，IPv6 必被拒——单独标出便于定位
    if (host.contains(':')) return null to "ipv6"
    val url = "http://$host:1400/xml/device_description.xml"
    if (pinLanUrl(url) == null) return null to "pin-reject"     // 非内网 IPv4 / 解析失败
    val desc = fetchLanText(url, maxBytes = 512 * 1024,
        connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs)
        ?: return null to "unreachable"                          // :1400 连不上 / 超时
    return if (isSonosDescription(desc)) sonosControlUrl(host) to "ok"
           else null to "not-sonos"                              // :1400 有响应但不是 Sonos
}
