// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaTimeTest {
    @Test fun secToHmsFormatsHoursMinutesSeconds() {
        assertEquals("0:00:00", secToHms(0.0))
        assertEquals("0:01:05", secToHms(65.0))
        assertEquals("1:02:03", secToHms(3723.0))
    }

    @Test fun hmsToSecParsesAndTolerates() {
        assertEquals(0.0, hmsToSec("0:00:00"), 0.001)
        assertEquals(65.0, hmsToSec("0:01:05"), 0.001)
        assertEquals(3723.0, hmsToSec("1:02:03"), 0.001)
        assertEquals(0.0, hmsToSec("NOT_IMPLEMENTED"), 0.001)
        assertEquals(0.0, hmsToSec(""), 0.001)
    }

    @Test fun escapeXmlEscapesFive() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", escapeXml("a&b<c>d\"e'f"))
    }
}