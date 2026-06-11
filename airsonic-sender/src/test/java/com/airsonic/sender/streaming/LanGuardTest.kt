// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanGuardTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun allowsSiteLocalAndLoopback() {
        assertTrue(isLanClient(ip("192.168.100.28")))   // Sonos 同网段
        assertTrue(isLanClient(ip("10.0.0.5")))
        assertTrue(isLanClient(ip("172.16.3.9")))
        assertTrue(isLanClient(ip("127.0.0.1")))        // 回环（本机自测/JVM 单测）
    }

    @Test fun rejectsPublicAndLinkLocalAndNull() {
        assertFalse(isLanClient(ip("8.8.8.8")))
        assertFalse(isLanClient(ip("169.254.1.1")))     // 链路本地
        assertFalse(isLanClient(null))
    }
}
