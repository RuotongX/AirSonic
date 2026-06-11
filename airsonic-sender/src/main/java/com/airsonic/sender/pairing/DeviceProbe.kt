// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.pairing

/** AirPlay `GET /info` 探测结论：是否只收 ALAC、是否需要 PIN/密码配对。 */
data class DeviceProbe(val requiresAlac: Boolean, val requiresPin: Boolean)

/**
 * 从 `/info` 的 bplist 解析出 [DeviceProbe]——真机踩坑换来的判定，钉死防回归：
 *  - ALAC：audioStream 编码表含 18(ALAC) 且不含 11(AAC)→设备只收 ALAC（如 Sonos）。
 *  - PIN：statusFlags 含 0x8/0x40/0x200 任一 → 需密码配对（Apple TV 设了访问限制）。
 * 纯逻辑，JVM 可测。
 */
fun parseDeviceProbe(plist: Map<*, *>): DeviceProbe {
    val saf = plist["supportedAudioFormatsExtended"] as? Map<*, *>
    val codes = (saf?.get("audioStream") as? List<*>)
        ?.mapNotNull { (it as? Long)?.toInt() ?: (it as? Int) } ?: emptyList()
    val alac = codes.contains(18) && !codes.contains(11)
    val sf = (plist["statusFlags"] as? Number)?.toLong() ?: 0L
    val pin = (sf and 0x40L) != 0L || (sf and 0x8L) != 0L || (sf and 0x200L) != 0L
    return DeviceProbe(alac, pin)
}
