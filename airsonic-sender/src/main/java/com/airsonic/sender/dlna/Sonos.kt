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
 * 把直播流的 http:// URL 改写为 Sonos 电台 scheme。
 * 新固件（6.4.2+）拒收裸 `http://` 电台 URI（UPnP 714 或静默不播）；
 * `x-rincon-mp3radio://` 让 Sonos 走电台播放管线（大缓冲、ICY 拉流），AAC 流同样适用。
 * 这是 SoCo `force_radio` / node-sonos 社区的标准做法。
 */
fun sonosRadioUri(httpUrl: String): String =
    httpUrl.replaceFirst(Regex("^https?://"), "x-rincon-mp3radio://")

/**
 * 直播音频流的 DIDL-Lite。与文件版（[buildDidl]）区别：
 *  - 类别 audioBroadcast（直播电台），不是 musicTrack；
 *  - protocolInfo 用最兼容的裸 `http-get:*:audio/aac:*`。
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

/**
 * Sonos 电台元数据（SoCo `play_uri(force_radio=True)` 同款模板）：
 * audioBroadcast 类别 + Rincon `desc`（SA_RINCON65031_ = TuneIn 电台服务 token），不带 res——
 * 真实 URI 由 SetAVTransportURI 的 CurrentURI（x-rincon-mp3radio://…）承载。
 */
fun buildSonosRadioDidl(title: String): String {
    val t = escapeXml(title)
    return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
        """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
        """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/">""" +
        """<item id="R:0/0/0" parentID="R:0/0" restricted="true">""" +
        """<dc:title>$t</dc:title>""" +
        """<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>""" +
        """<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">SA_RINCON65031_</desc>""" +
        """</item></DIDL-Lite>"""
}

/**
 * 无限长 WAV 直播流的 DIDL-Lite（swyh-rs 同款路线）：musicTrack 类别 + 普通 http-get，
 * 配合假大 Content-Length 当成「超长曲目」播。用于 AAC 电台管线不出声时的兼容兜底。
 */
fun buildLiveWavDidl(title: String, url: String): String {
    val t = escapeXml(title)
    val u = escapeXml(url)
    return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
        """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
        """<item id="0" parentID="-1" restricted="1">""" +
        """<dc:title>$t</dc:title>""" +
        """<upnp:class>object.item.audioItem.musicTrack</upnp:class>""" +
        """<res protocolInfo="http-get:*:audio/wav:*">$u</res>""" +
        """</item></DIDL-Lite>"""
}
