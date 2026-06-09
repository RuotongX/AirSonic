package com.airsonic.sender.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosTest {
    @Test fun controlUrl_fixedPath() {
        assertEquals(
            "http://192.168.1.20:1400/MediaRenderer/AVTransport/Control",
            sonosControlUrl("192.168.1.20")
        )
    }

    @Test fun detect_sonos_description() {
        val xml = """<root><device><manufacturer>Sonos, Inc.</manufacturer>
            <modelName>Sonos Arc</modelName></device></root>"""
        assertTrue(isSonosDescription(xml))
    }

    @Test fun detect_rejects_nonSonos() {
        val xml = """<root><device><manufacturer>Kodi</manufacturer></device></root>"""
        assertFalse(isSonosDescription(xml))
    }

    @Test fun liveDidl_hasAacStreamProtocolInfo() {
        val didl = buildLiveAudioDidl("AirSonic Live", "http://10.0.0.2:9000/live.aac")
        assertTrue(didl.contains("http://10.0.0.2:9000/live.aac"))
        assertTrue(didl.contains("audio/aac"))
        assertTrue(didl.contains("DLNA.ORG_OP=00"))                 // 不可 seek
        assertTrue(didl.contains("object.item.audioItem.audioBroadcast"))
    }
}
