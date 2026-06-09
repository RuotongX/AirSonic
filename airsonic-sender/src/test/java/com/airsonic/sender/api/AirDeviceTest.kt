// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirDeviceTest {
    @Test fun dlnaDeviceCarriesControlUrl() {
        val d = AirDevice(
            name = "Kodi", host = "192.168.1.5", port = 8200,
            type = DeviceType.DLNA, controlUrl = "http://192.168.1.5:8200/ctl/AVTransport",
        )
        assertEquals(DeviceType.DLNA, d.type)
        assertEquals("http://192.168.1.5:8200/ctl/AVTransport", d.controlUrl)
        assertEquals("192.168.1.5:8200", d.id)
    }

    @Test fun airplayDeviceHasNullControlUrl() {
        val d = AirDevice(name = "HomePod", host = "10.0.0.2", port = 7000)
        assertNull(d.controlUrl)
    }
}