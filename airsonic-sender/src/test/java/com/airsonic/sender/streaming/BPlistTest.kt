// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BPlistTest {
    @Test fun roundTrip_mapWithCommonTypes() {
        val src = linkedMapOf<String, Any?>(
            "s" to "hello", "i" to 42L, "b" to true, "d" to 1.5,
        )
        @Suppress("UNCHECKED_CAST")
        val out = BPlist.decode(BPlist.encode(src)) as Map<String, Any?>
        assertEquals("hello", out["s"])
        assertEquals(42L, out["i"])
        assertEquals(true, out["b"])
        assertEquals(1.5, out["d"] as Double, 1e-9)
    }

    @Test fun decode_rejectsTooShort() {
        val e = runCatching { BPlist.decode("bplist00".toByteArray()) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun decode_rejectsBadMagic() {
        val bad = ByteArray(40) { 0 }
        assertTrue(runCatching { BPlist.decode(bad) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun decode_rejectsHugeNumObjects_noOom() {
        // 合法头 + trailer 里 numObjects 设成 0x7FFFFFFF：旧代码会 IntArray(2G) → OOM。
        // 现在必须被边界校验挡成 IllegalArgumentException，且不分配巨数组。
        val data = ByteArray(48)
        System.arraycopy("bplist00".toByteArray(), 0, data, 0, 8)
        val trailer = data.size - 32
        data[trailer + 6] = 1            // offsetSize
        data[trailer + 7] = 1            // refSize
        // numObjects (trailer+8, 8B BE) = 0x7FFFFFFF
        data[trailer + 12] = 0x7F; data[trailer + 13] = 0xFF.toByte()
        data[trailer + 14] = 0xFF.toByte(); data[trailer + 15] = 0xFF.toByte()
        val e = runCatching { BPlist.decode(data) }.exceptionOrNull()
        assertTrue("应被边界校验拒绝，实际: $e", e is IllegalArgumentException)
    }

    @Test fun decode_rejectsNegativeNumObjects() {
        val data = ByteArray(48)
        System.arraycopy("bplist00".toByteArray(), 0, data, 0, 8)
        val trailer = data.size - 32
        data[trailer + 6] = 1; data[trailer + 7] = 1
        for (i in 8..15) data[trailer + i] = 0xFF.toByte()   // numObjects = -1
        assertTrue(runCatching { BPlist.decode(data) }.exceptionOrNull() is IllegalArgumentException)
    }
}
