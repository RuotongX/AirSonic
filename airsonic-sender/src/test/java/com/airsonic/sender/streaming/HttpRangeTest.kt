// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRangeTest {
    @Test fun fullWhenNoHeader() {
        assertEquals(0L to 99L, HttpRange.parse(null, 100))
    }
    @Test fun startToEnd() {
        assertEquals(10L to 49L, HttpRange.parse("bytes=10-49", 100))
    }
    @Test fun openEnded() {
        assertEquals(10L to 99L, HttpRange.parse("bytes=10-", 100))
    }
    @Test fun suffix() {
        assertEquals(80L to 99L, HttpRange.parse("bytes=-20", 100))
    }
    @Test fun clampsEndToLength() {
        assertEquals(90L to 99L, HttpRange.parse("bytes=90-200", 100))
    }
    @Test fun invalidReturnsNull() {
        assertNull(HttpRange.parse("bytes=abc", 100))
    }
}