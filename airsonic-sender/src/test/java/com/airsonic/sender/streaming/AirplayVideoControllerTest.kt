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

    @Test fun commandBodyIsNestedBplist() {
        // tvOS 26 play-queue：/command 体 = bplist{"params":{"data":<内层 bplist 命令>}}
        val body = AirplayVideoController.buildCommandBody(linkedMapOf<String, Any?>(
            "type" to "setRate", "rate" to 1.0))
        @Suppress("UNCHECKED_CAST")
        val outer = BPlist.decode(body) as Map<Any?, Any?>
        @Suppress("UNCHECKED_CAST")
        val params = outer["params"] as Map<Any?, Any?>
        @Suppress("UNCHECKED_CAST")
        val inner = BPlist.decode(params["data"] as ByteArray) as Map<Any?, Any?>
        assertEquals("setRate", inner["type"])
        assertEquals(1.0, (inner["rate"] as Number).toDouble(), 0.0001)
    }
}