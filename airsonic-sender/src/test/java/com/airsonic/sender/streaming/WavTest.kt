package com.airsonic.sender.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavTest {
    @Test fun header_is44BytesRiffPcm() {
        val h = wavStreamHeader(44100, 2, 16)
        assertEquals(44, h.size)
        assertArrayEquals("RIFF".toByteArray(), h.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(), h.copyOfRange(8, 12))
        assertArrayEquals("fmt ".toByteArray(), h.copyOfRange(12, 16))
        assertArrayEquals("data".toByteArray(), h.copyOfRange(36, 40))
        // RIFF/data 长度 = 0xFFFFFFFF（无限长流）
        for (off in intArrayOf(4, 40)) {
            for (i in 0 until 4) assertEquals(0xFF.toByte(), h[off + i])
        }
    }

    @Test fun header_encodesFormatFields() {
        val h = wavStreamHeader(44100, 2, 16)
        fun le16(off: Int) = (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8)
        fun le32(off: Int) = le16(off).toLong() or (le16(off + 2).toLong() shl 16)
        assertEquals(16L, le32(16))            // fmt chunk size
        assertEquals(1, le16(20))              // PCM
        assertEquals(2, le16(22))              // channels
        assertEquals(44100L, le32(24))         // sample rate
        assertEquals(44100L * 2 * 2, le32(28)) // byte rate
        assertEquals(4, le16(32))              // block align
        assertEquals(16, le16(34))             // bits per sample
    }
}
