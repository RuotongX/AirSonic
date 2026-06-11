// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {
    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    @Test fun silenceIsZero() {
        assertEquals(0f, pcmPeak(ByteArray(64)), 1e-6f)
    }

    @Test fun fullScaleIsOne() {
        assertEquals(1f, pcmPeak(le16(32767) + le16(-32768)), 1e-3f)
    }

    @Test fun halfScaleAboutHalf() {
        val p = pcmPeak(le16(16384))
        assertTrue("约 0.5，实际 $p", p in 0.45f..0.55f)
    }

    @Test fun emptyOrTinyDoesNotCrash() {
        assertEquals(0f, pcmPeak(ByteArray(0)), 1e-6f)
        assertEquals(0f, pcmPeak(byteArrayOf(1)), 1e-6f)   // 不足一个样本
    }
}
