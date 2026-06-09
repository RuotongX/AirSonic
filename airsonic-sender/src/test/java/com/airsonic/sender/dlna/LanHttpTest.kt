// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** pinLanUrl 的 SSRF 防护校验。 */
class LanHttpTest {
    @Test fun acceptsRfc1918() {
        for (raw in listOf(
            "http://192.168.1.5:8200/ctl/AVT",
            "http://10.0.0.9/desc.xml",
            "http://172.16.3.4:49152/x",
        )) assertNotNull("应接受 $raw", pinLanUrl(raw))
    }

    @Test fun pinsIpAndPreservesHostHeaderWithPort() {
        val p = pinLanUrl("http://192.168.1.5:8200/ctl/AVT?a=1")!!
        assertEquals("192.168.1.5", p.url.host)         // 钉到 IP 字面量
        assertEquals(8200, p.url.port)
        assertEquals("/ctl/AVT?a=1", p.url.file)        // 路径+查询保留
        assertEquals("192.168.1.5:8200", p.hostHeader)  // Host 头保留原主机:端口
    }

    @Test fun hostHeaderWithoutPortWhenAbsent() {
        val p = pinLanUrl("http://10.0.0.9/desc.xml")!!
        assertEquals("10.0.0.9", p.hostHeader)
    }

    @Test fun rejectsLoopback() = assertNull(pinLanUrl("http://127.0.0.1:8200/x"))

    @Test fun rejectsLinkLocalMetadata() = assertNull(pinLanUrl("http://169.254.169.254/latest/meta-data"))

    @Test fun rejectsPublic() = assertNull(pinLanUrl("http://93.184.216.34/ctl"))

    @Test fun rejectsNonHttpScheme() = assertNull(pinLanUrl("file:///etc/passwd"))

    @Test fun rejectsUserInfo() = assertNull(pinLanUrl("http://user@192.168.1.5/x"))

    @Test fun rejectsGarbage() = assertNull(pinLanUrl("not a url"))
}