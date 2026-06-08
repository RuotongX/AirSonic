package com.airsonic.sender.streaming

import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceCapabilities
import com.airsonic.sender.api.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCapabilityTest {
    private fun dev(type: DeviceType, video: Boolean) =
        AirDevice("d", "1.2.3.4", 7000, type, DeviceCapabilities(supportsVideo = video))

    @Test fun appleTvSupportsVideo() {
        assertEquals(true, dev(DeviceType.APPLE_TV, true).capabilities.supportsVideo)
    }
    @Test fun homepodNoVideo() {
        assertEquals(false, dev(DeviceType.HOMEPOD, false).capabilities.supportsVideo)
    }
}
