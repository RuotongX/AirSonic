// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import java.net.InetAddress

/**
 * 本机流服务的来源准入：服务绑 0.0.0.0、仅靠随机 token 路径混淆不算认证。
 * 只放行**局域网内网(site-local/RFC1918)**与**回环**来源,拒掉其它(公网/链路本地等)——
 * 投送目标(电视/Sonos)都是同网段内网设备,回环用于本机自测/JVM 单测。
 */
fun isLanClient(addr: InetAddress?): Boolean =
    addr != null && (addr.isSiteLocalAddress || addr.isLoopbackAddress)
