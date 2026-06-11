// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanIpTest {
    @Test fun picksSameSubnetAsTarget() {
        // 华为多网卡：热点 192.168.43.x + Wi-Fi 192.168.100.x，目标在 100 段 → 必须选 100 段
        val cands = listOf("192.168.43.1", "192.168.100.35")
        assertEquals("192.168.100.35", pickLanIpForTarget(cands, "192.168.100.28"))
    }

    @Test fun fallsBackToFirstUsableWhenNoMatch() {
        assertEquals("10.0.0.5", pickLanIpForTarget(listOf("10.0.0.5"), "192.168.1.9"))
        assertEquals("10.0.0.5", pickLanIpForTarget(listOf("10.0.0.5"), ""))
    }

    @Test fun excludesLinkLocalAndEmpty() {
        assertEquals("192.168.1.7", pickLanIpForTarget(listOf("169.254.3.3", "192.168.1.7"), ""))
        assertNull(pickLanIpForTarget(listOf("169.254.3.3"), "192.168.1.9"))
        assertNull(pickLanIpForTarget(emptyList(), "192.168.1.9"))
    }
}
