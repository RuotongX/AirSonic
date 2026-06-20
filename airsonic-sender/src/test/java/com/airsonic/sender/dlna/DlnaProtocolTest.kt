// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {
    @Test fun soapActionDefaultsToAvTransport() {
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", soapAction("Play"))
    }

    @Test fun soapActionUsesRenderingControlNamespace() {
        assertEquals(
            "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"",
            soapAction("SetVolume", RENDERING_CONTROL)
        )
    }

    @Test fun soapBodyEmbedsServiceTypeAndInstanceId() {
        val body = soapBody("SetVolume",
            "<Channel>Master</Channel><DesiredVolume>42</DesiredVolume>", RENDERING_CONTROL)
        assertTrue(body.contains("xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\""))
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
        assertTrue(body.contains("<DesiredVolume>42</DesiredVolume>"))
        assertTrue(body.contains("<u:SetVolume"))
    }

    @Test fun parseCurrentVolumeReadsValue() {
        assertEquals(37, parseCurrentVolume("<u:GetVolumeResponse><CurrentVolume>37</CurrentVolume></u:GetVolumeResponse>"))
    }

    @Test fun parseCurrentVolumeNullWhenMissing() {
        assertNull(parseCurrentVolume("<u:GetVolumeResponse></u:GetVolumeResponse>"))
    }
}
