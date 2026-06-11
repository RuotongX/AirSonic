// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProbeTest {
    private fun plist(codes: List<Long>?, statusFlags: Long?): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        if (codes != null) m["supportedAudioFormatsExtended"] = mapOf("audioStream" to codes)
        if (statusFlags != null) m["statusFlags"] = statusFlags
        return m
    }

    @Test fun alac_whenHas18NotHas11() {
        assertTrue(parseDeviceProbe(plist(listOf(18L), null)).requiresAlac)        // 只 ALAC
        assertFalse(parseDeviceProbe(plist(listOf(18L, 11L), null)).requiresAlac)  // 有 AAC → 不强制
        assertFalse(parseDeviceProbe(plist(listOf(11L), null)).requiresAlac)
        assertFalse(parseDeviceProbe(plist(null, null)).requiresAlac)
    }

    @Test fun pin_whenStatusFlagBitSet() {
        assertTrue(parseDeviceProbe(plist(null, 0x8L)).requiresPin)
        assertTrue(parseDeviceProbe(plist(null, 0x40L)).requiresPin)
        assertTrue(parseDeviceProbe(plist(null, 0x200L)).requiresPin)
        assertFalse(parseDeviceProbe(plist(null, 0x4L)).requiresPin)
        assertFalse(parseDeviceProbe(plist(null, 0L)).requiresPin)
    }

    @Test fun handlesIntAndLongCodes() {
        // bplist 整数可能解成 Int 或 Long，两者都要认
        assertTrue(parseDeviceProbe(mapOf(
            "supportedAudioFormatsExtended" to mapOf("audioStream" to listOf(18)),
        )).requiresAlac)
    }
}
