package com.airsonic.sender.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseParseTest {
    @Test fun positionInfoExtractsRelTimeAndDuration() {
        val xml = "<s:Envelope><s:Body><u:GetPositionInfoResponse>" +
            "<TrackDuration>0:03:00</TrackDuration><RelTime>0:00:30</RelTime>" +
            "</u:GetPositionInfoResponse></s:Body></s:Envelope>"
        val (pos, dur) = parsePositionInfo(xml)!!
        assertEquals(30.0, pos, 0.001)
        assertEquals(180.0, dur, 0.001)
    }

    @Test fun positionInfoTreatsNotImplementedAsZero() {
        val xml = "<RelTime>NOT_IMPLEMENTED</RelTime><TrackDuration></TrackDuration>"
        val (pos, dur) = parsePositionInfo(xml)!!
        assertEquals(0.0, pos, 0.001)
        assertEquals(0.0, dur, 0.001)
    }

    @Test fun positionInfoNullWhenNoFields() {
        assertNull(parsePositionInfo("<nothing/>"))
    }

    @Test fun soapFaultErrorCodeExtracted() {
        val xml = "<s:Fault><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>716</errorCode><errorDescription>Resource not found</errorDescription>" +
            "</UPnPError></detail></s:Fault>"
        assertEquals("716 Resource not found", parseSoapError(xml))
    }

    @Test fun soapErrorNullWhenNoFault() {
        assertNull(parseSoapError("<ok/>"))
    }
}
