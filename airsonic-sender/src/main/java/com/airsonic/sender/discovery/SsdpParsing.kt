// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.discovery

import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** 解析出的一台 DLNA MediaRenderer。 */
data class DlnaRenderer(
    val friendlyName: String,
    val avTransportControlUrl: String,
)

/** 从 SSDP 包（M-SEARCH 200 响应或 NOTIFY）取 LOCATION；byebye 返回 null。 */
fun ssdpLocation(packet: String): String? {
    var location: String? = null
    var byebye = false
    for (raw in packet.split("\r\n", "\n")) {
        val line = raw.trim()
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val key = line.substring(0, idx).trim().lowercase()
        val value = line.substring(idx + 1).trim()
        when (key) {
            "location" -> location = value
            "nts" -> if (value.equals("ssdp:byebye", true)) byebye = true
        }
    }
    return if (byebye) null else location
}

/** 把 ref（相对或绝对）按 base 解析为绝对 URL。 */
fun resolveUrl(base: String, ref: String): String =
    runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)

/** 设备描述 XML 上限：UPnP 描述很小，超过即拒（防超大恶意输入）。 */
private const val MAX_DESC_BYTES = 512 * 1024

/** 解析设备描述 XML：必须含 AVTransport:1 服务，返回 friendlyName + 绝对 controlURL。 */
fun parseRenderer(xml: String, locationUrl: String): DlnaRenderer? {
    if (xml.length > MAX_DESC_BYTES) return null
    val doc = runCatching {
        // 描述来自局域网未信任设备 → 防 XXE：禁 DOCTYPE/外部实体（disallow-doctype-decl 即可挡住三种变体，其余为纵深防御）。
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            for ((feature, on) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            )) runCatching { setFeature(feature, on) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }.getOrNull() ?: return null

    fun firstText(tag: String): String? {
        val nodes = doc.getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
    }

    // 在 <service> 列表里找 AVTransport 的 controlURL
    var avtControlUrl: String? = null
    val services = doc.getElementsByTagName("service")
    for (i in 0 until services.length) {
        val children = services.item(i).childNodes
        var type: String? = null
        var ctrl: String? = null
        for (j in 0 until children.length) {
            val n = children.item(j)
            if (n.nodeType != Node.ELEMENT_NODE) continue
            when (n.nodeName.lowercase()) {
                "servicetype" -> type = n.textContent?.trim()
                "controlurl" -> ctrl = n.textContent?.trim()
            }
        }
        if (type?.contains("AVTransport") == true && !ctrl.isNullOrEmpty()) { avtControlUrl = ctrl; break }
    }

    val rel = avtControlUrl ?: return null
    val base = firstText("URLBase")?.ifEmpty { null } ?: locationUrl
    val name = cleanFriendlyName(firstText("friendlyName")?.ifEmpty { null } ?: "DLNA")
    return DlnaRenderer(friendlyName = name, avTransportControlUrl = resolveUrl(base, rel))
}

private val IPV4 = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

/**
 * 清理冗长的 friendlyName。Sonos 形如 "192.168.x.x - Sonos Arc - RINCON_xxx"——
 * 取既不是 IP 也不是 RINCON 序列号的型号段。其它设备：超长则截断。
 */
fun cleanFriendlyName(raw: String): String {
    val t = raw.trim()
    if (t.contains(" - ") && t.contains("RINCON", ignoreCase = true)) {
        t.split(" - ").map { it.trim() }.firstOrNull {
            it.isNotEmpty() && !it.startsWith("RINCON", true) && !IPV4.matches(it)
        }?.let { return it }
    }
    return if (t.length > 28) t.take(27) + "…" else t
}