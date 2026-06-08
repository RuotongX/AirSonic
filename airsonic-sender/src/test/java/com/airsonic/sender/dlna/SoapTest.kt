package com.airsonic.sender.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoapTest {
    @Test fun soapActionHeaderFormat() {
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", soapAction("Play"))
    }

    @Test fun envelopeWrapsBodyWithInstanceId() {
        val xml = soapBody("Play", "<Speed>1</Speed>")
        assertTrue(xml.contains("<s:Envelope"))
        assertTrue(xml.contains("""<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">"""))
        assertTrue(xml.contains("<InstanceID>0</InstanceID>"))
        assertTrue(xml.contains("<Speed>1</Speed>"))
        assertTrue(xml.contains("</u:Play>"))
    }

    @Test fun setUriBodyEmbedsCdataMetadata() {
        val didl = buildDidl("t", "http://h/u", "video/mp4", true)
        val params = setAvTransportUriParams("http://h/u", didl)
        assertTrue(params.contains("<CurrentURI>http://h/u</CurrentURI>"))
        assertTrue(params.contains("<![CDATA["))
        assertTrue(params.contains("DIDL-Lite"))
    }
}
