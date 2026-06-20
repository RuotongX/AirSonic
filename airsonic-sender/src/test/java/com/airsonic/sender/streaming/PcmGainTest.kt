// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmGainTest {
    private fun le(sample: Int) = byteArrayOf((sample and 0xFF).toByte(), ((sample shr 8) and 0xFF).toByte())
    private fun read(buf: ByteArray, i: Int) = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)

    @Test fun gainOneIsIdentity() {
        val buf = le(1000) + le(-1000)
        scalePcm16(buf, buf.size, 1f)
        assertEquals(1000, read(buf, 0)); assertEquals(-1000, read(buf, 2))
    }

    @Test fun gainZeroSilences() {
        val buf = le(1000) + le(-32768)
        scalePcm16(buf, buf.size, 0f)
        assertEquals(0, read(buf, 0)); assertEquals(0, read(buf, 2))
    }

    @Test fun gainHalfHalvesSamples() {
        val buf = le(1000) + le(-2000)
        scalePcm16(buf, buf.size, 0.5f)
        assertEquals(500, read(buf, 0)); assertEquals(-1000, read(buf, 2))
    }

    @Test fun overdriveClamps() {
        val buf = le(30000) + le(-30000)
        scalePcm16(buf, buf.size, 2f)
        assertEquals(32767, read(buf, 0)); assertEquals(-32768, read(buf, 2))
    }
}
