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
): String? {
    val desc = fetchLanText(
        "http://$host:1400/xml/device_description.xml",
        maxBytes = 512 * 1024,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    ) ?: return null
    return if (isSonosDescription(desc)) sonosControlUrl(host) else null
}
