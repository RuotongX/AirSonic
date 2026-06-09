// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

/** Sonos 专用辅助：控制地址、设备识别、直播流 DIDL。 */

/** Sonos 全系固定的 AVTransport 控制地址。 */
fun sonosControlUrl(host: String): String =
    "http://$host:1400/MediaRenderer/AVTransport/Control"

/** Sonos 设备描述 XML 判定（manufacturer 含 Sonos）。 */
fun isSonosDescription(xml: String): Boolean =
    Regex("(?i)<manufacturer>\\s*Sonos").containsMatchIn(xml)

/**
 * 直播音频流的 DIDL-Lite。与文件版（[buildDidl]）区别：
 *  - 类别 audioBroadcast（直播电台），不是 musicTrack；
 *  - protocolInfo DLNA.ORG_OP=00（不可 seek/range），FLAGS 标流式实时。
 */
fun buildLiveAudioDidl(title: String, url: String): String {
    val t = escapeXml(title)
    val u = escapeXml(url)
    val pInfo = "http-get:*:audio/aac:*"
    return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
        """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
        """<item id="0" parentID="-1" restricted="1">""" +
        """<dc:title>$t</dc:title>""" +
        """<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>""" +
        """<res protocolInfo="$pInfo">$u</res>""" +
        """</item></DIDL-Lite>"""
}
