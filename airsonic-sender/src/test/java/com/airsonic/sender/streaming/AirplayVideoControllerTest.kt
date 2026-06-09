// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class AirplayVideoControllerTest {
    @Test fun playBodyHasLocationAndStart() {
        val body = AirplayVideoController.buildPlayBody("http://10.0.0.2:5555/v/x", 0.0)
        @Suppress("UNCHECKED_CAST")
        val pl = BPlist.decode(body) as Map<Any?, Any?>
        assertEquals("http://10.0.0.2:5555/v/x", pl["Content-Location"])
        assertEquals(0.0, (pl["Start-Position-Seconds"] as Number).toDouble(), 0.0001)
    }
}