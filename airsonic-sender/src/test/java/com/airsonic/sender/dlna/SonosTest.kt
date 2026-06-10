// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

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
        assertTrue(didl.contains("http-get:*:audio/aac:*"))        // 最兼容裸 protocolInfo
        assertTrue(didl.contains("object.item.audioItem.audioBroadcast"))
    }

    @Test fun radioUri_rewritesHttpScheme() {
        assertEquals(
            "x-rincon-mp3radio://10.0.0.2:9000/live.aac",
            sonosRadioUri("http://10.0.0.2:9000/live.aac")
        )
        assertEquals(
            "x-rincon-mp3radio://10.0.0.2/a",
            sonosRadioUri("https://10.0.0.2/a")
        )
    }

    @Test fun radioDidl_socoTemplate() {
        val didl = buildSonosRadioDidl("AirSonic <Live>")
        assertTrue(didl.contains("object.item.audioItem.audioBroadcast"))
        assertTrue(didl.contains("SA_RINCON65031_"))                 // TuneIn 电台服务 token
        assertTrue(didl.contains("""id="R:0/0/0""""))
        assertTrue(didl.contains("AirSonic &lt;Live&gt;"))           // 标题已转义
        assertFalse(didl.contains("<res"))                           // 电台模板不带 res
    }

    @Test fun wavDidl_musicTrackWithWavProtocolInfo() {
        val didl = buildLiveWavDidl("AirSonic Live", "http://10.0.0.2:9000/live.wav")
        assertTrue(didl.contains("object.item.audioItem.musicTrack"))
        assertTrue(didl.contains("http-get:*:audio/wav:*"))
        assertTrue(didl.contains("http://10.0.0.2:9000/live.wav"))
    }
}
