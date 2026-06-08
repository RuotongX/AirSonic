package com.airsonic.sender.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 校验 DlnaController 的 SSRF 防护：非局域网 controlUrl 必须被拒、不触发任何网络请求。 */
class DlnaControllerTest {
    @Test fun rejectsLoopbackControlUrl() {
        val c = DlnaController("http://127.0.0.1:8200/ctl/AVT")
        assertFalse(c.play())
        assertEquals("rejected non-LAN controlUrl", c.lastError)
    }

    @Test fun rejectsPublicControlUrl() {
        val c = DlnaController("http://93.184.216.34/ctl") // 公网 IP
        assertFalse(c.stop())
        assertEquals("rejected non-LAN controlUrl", c.lastError)
    }

    @Test fun rejectsLinkLocalMetadataUrl() {
        val c = DlnaController("http://169.254.169.254/latest/meta-data") // 链路本地/云元数据
        assertFalse(c.pause())
        assertEquals("rejected non-LAN controlUrl", c.lastError)
    }

    @Test fun rejectsNonHttpScheme() {
        val c = DlnaController("file:///etc/passwd")
        assertFalse(c.play())
        assertEquals("rejected non-LAN controlUrl", c.lastError)
    }
}
