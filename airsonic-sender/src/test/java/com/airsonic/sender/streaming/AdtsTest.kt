// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdtsTest {
    @Test fun header_44100_stereo_knownBytes() {
        // aac 负载 100B → frameLen = 107
        val h = adtsHeader(sampleRate = 44100, channels = 2, aacFrameLen = 100)
        assertEquals(7, h.size)
        assertEquals(0xFF.toByte(), h[0])            // syncword 高 8 位
        assertEquals(0xF1.toByte(), h[1])            // sync 低4 + MPEG4 + layer00 + 无CRC
        assertEquals(0x50.toByte(), h[2])            // profile=1, freqIdx=4, chan 高位
        // frameLen=107=0b0000001101011 → 高2位=0
        assertEquals(0x80.toByte(), h[3])            // chan 低2位(=2<<6) | frameLen 高2位(0)
        assertEquals(((107 shr 3) and 0xFF).toByte(), h[4])
        assertEquals((((107 and 7) shl 5) or 0x1F).toByte(), h[5])
        assertEquals(0xFC.toByte(), h[6])
    }

    @Test fun frameLength_isPayloadPlus7() {
        val h = adtsHeader(44100, 2, 0)
        val len = ((h[3].toInt() and 0x03) shl 11) or
                  ((h[4].toInt() and 0xFF) shl 3) or
                  ((h[5].toInt() and 0xE0) shr 5)
        assertEquals(7, len)  // 空负载帧长 = 头长
    }

    @Test fun freqIndex_48000_is3() {
        val h = adtsHeader(48000, 2, 0)
        val freqIdx = (h[2].toInt() and 0x3C) shr 2
        assertEquals(3, freqIdx)
    }
}
