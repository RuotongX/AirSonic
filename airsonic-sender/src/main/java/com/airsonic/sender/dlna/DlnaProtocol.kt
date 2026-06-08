package com.airsonic.sender.dlna

/** DLNA/UPnP 纯协议逻辑（无 Android 依赖，便于 JVM 单测）。 */

/** DLNA 内容特征串：OP=01 支持 byte-range/seek；与 LocalMediaHttpServer 的 contentFeatures 头一致。 */
const val DLNA_CONTENT_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"

/** XML 文本转义（5 个实体）。 */
fun escapeXml(s: String): String = buildString(s.length + 16) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}

/** 秒 → H:MM:SS（UPnP REL_TIME）。 */
fun secToHms(sec: Double): String {
    val total = sec.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "%d:%02d:%02d".format(h, m, s)
}

/** H:MM:SS → 秒；空/NOT_IMPLEMENTED/非法 → 0.0。 */
fun hmsToSec(hms: String): Double {
    val t = hms.trim()
    if (t.isEmpty() || t.equals("NOT_IMPLEMENTED", true)) return 0.0
    val parts = t.split(":")
    return runCatching {
        when (parts.size) {
            3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()
            2 -> parts[0].toLong() * 60 + parts[1].toDouble()
            1 -> parts[0].toDouble()
            else -> 0.0
        }
    }.getOrDefault(0.0)
}

/** 最小 DIDL-Lite 元数据（title/url 已转义）。 */
fun buildDidl(title: String, url: String, mime: String, isVideo: Boolean): String {
    val cls = if (isVideo) "object.item.videoItem" else "object.item.audioItem.musicTrack"
    val proto = "http-get:*:$mime:$DLNA_CONTENT_FEATURES"
    return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
        """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
        """<item id="0" parentID="-1" restricted="1">""" +
        """<dc:title>${escapeXml(title)}</dc:title>""" +
        """<upnp:class>$cls</upnp:class>""" +
        """<res protocolInfo="${escapeXml(proto)}">${escapeXml(url)}</res>""" +
        """</item></DIDL-Lite>"""
}

const val AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

/** SOAPACTION 头值（含外层引号）。 */
fun soapAction(action: String): String = "\"$AVTRANSPORT#$action\""

/** 完整 SOAP 信封；paramsXml 为动作参数（不含 InstanceID，函数自动加）。 */
fun soapBody(action: String, paramsXml: String): String =
    """<?xml version="1.0" encoding="utf-8"?>""" +
    """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
    """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
    """<u:$action xmlns:u="$AVTRANSPORT"><InstanceID>0</InstanceID>$paramsXml</u:$action>""" +
    """</s:Body></s:Envelope>"""

/** SetAVTransportURI 的参数：URI 转义 + DIDL 用 CDATA 包裹。 */
fun setAvTransportUriParams(url: String, didl: String): String =
    "<CurrentURI>${escapeXml(url)}</CurrentURI>" +
    "<CurrentURIMetaData><![CDATA[$didl]]></CurrentURIMetaData>"
