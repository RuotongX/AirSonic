// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeControllerTest {
    @Test fun pctToDbBoundaries() {
        assertEquals(-144.0, pctToAirplayDb(0), 0.001)
        assertEquals(0.0, pctToAirplayDb(100), 0.001)
        assertEquals(-15.0, pctToAirplayDb(50), 0.001)
        assertEquals(-29.7, pctToAirplayDb(1), 0.001)
        assertEquals(-144.0, pctToAirplayDb(-5), 0.001)   // 越界负值当静音
    }

    @Test fun gainControllerMapsPercentToFloat() {
        val c = GainVolumeController()
        assertTrue(c.setVolume(50)); assertEquals(0.5f, c.gain, 0.001f)
        assertEquals(50, c.getVolume())
    }

    @Test fun gainControllerMuteRestoresPrevious() {
        val c = GainVolumeController()
        c.setVolume(80)
        c.setMute(true); assertEquals(0f, c.gain, 0.001f)
        c.setMute(false); assertEquals(0.8f, c.gain, 0.001f)
    }
}
