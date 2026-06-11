// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.discovery

/**
 * 从本机候选 IPv4 中挑出与 [target] 同 /24 网段的那个——华为多网卡（Wi-Fi + 蜂窝/热点）
 * 上若选错网卡，投送目标连不上我们的流。无同网段则回退第一个可用候选。
 * [candidates] 应已是 IPv4、非回环。纯逻辑，JVM 可测。
 */
fun pickLanIpForTarget(candidates: List<String>, target: String): String? {
    val usable = candidates.filter { !it.startsWith("169.254") }   // 排除链路本地
    val prefix = target.substringBeforeLast('.', "")
    if (prefix.isNotEmpty()) usable.firstOrNull { it.startsWith("$prefix.") }?.let { return it }
    return usable.firstOrNull()
}
