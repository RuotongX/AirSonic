// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SsdpParsingTest {
    @Test fun extractsLocationFromMSearchResponse() {
        val pkt = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.1.5:8200/rootDesc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.5:8200/rootDesc.xml", ssdpLocation(pkt))
    }

    @Test fun extractsLocationFromNotifyAlive() {
        val pkt = "NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\n" +
            "Location: http://10.0.0.9:49152/desc.xml\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://10.0.0.9:49152/desc.xml", ssdpLocation(pkt))
    }

    @Test fun byebyeReturnsNull() {
        val pkt = "NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nLocation: http://x/y\r\n\r\n"
        assertNull(ssdpLocation(pkt))
    }

    @Test fun resolveRelativeControlUrl() {
        assertEquals("http://192.168.1.5:8200/ctl/AVTransport",
            resolveUrl("http://192.168.1.5:8200/rootDesc.xml", "/ctl/AVTransport"))
        assertEquals("http://192.168.1.5:8200/base/ctl",
            resolveUrl("http://192.168.1.5:8200/base/desc.xml", "ctl"))
        assertEquals("http://h/abs", resolveUrl("http://192.168.1.5:8200/x", "http://h/abs"))
    }

    @Test fun parsesDescriptionFriendlyNameAndAvTransportControlUrl() {
        val xml = """
            <root><device>
              <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>Living Room TV</friendlyName>
              <serviceList>
                <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                  <controlURL>/ctl/RC</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                  <controlURL>/ctl/AVT</controlURL></service>
              </serviceList>
            </device></root>
        """.trimIndent()
        val r = parseRenderer(xml, "http://192.168.1.5:8200/rootDesc.xml")!!
        assertEquals("Living Room TV", r.friendlyName)
        assertEquals("http://192.168.1.5:8200/ctl/AVT", r.avTransportControlUrl)
    }

    @Test fun parseRendererNullWhenNoAvTransport() {
        val xml = "<root><device><friendlyName>X</friendlyName>" +
            "<serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
            "<controlURL>/c</controlURL></service></serviceList></device></root>"
        assertNull(parseRenderer(xml, "http://h:1/d.xml"))
    }

    @Test fun rejectsXmlWithDoctypeXxe() {
        // 防 XXE：含 DOCTYPE 的描述（来自未信任局域网设备）必须被拒，返回 null。
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY x \"pwn\">]>" +
            "<root><device><friendlyName>X</friendlyName><serviceList><service>" +
            "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>" +
            "<controlURL>/c</controlURL></service></serviceList></device></root>"
        assertNull(parseRenderer(xml, "http://h:1/d.xml"))
    }
}