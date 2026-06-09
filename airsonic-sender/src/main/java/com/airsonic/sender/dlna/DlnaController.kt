// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import android.util.Log
import java.net.HttpURLConnection

/** 对一个 AVTransport controlUrl 发 SOAP 控制动作。 */
class DlnaController(private val controlUrl: String) {
    var lastError: String = ""
        private set

    /**
     * 防 SSRF / DNS rebinding：controlUrl 来自 SSDP 发现的设备描述（未信任源）。
     * 构造时校验并把主机钉到解析出的局域网 IP（连接时不再二次 DNS）。见 [pinLanUrl]。
     */
    private val pinned: PinnedUrl? = pinLanUrl(controlUrl)

    fun setUri(url: String, didl: String): Boolean =
        action("SetAVTransportURI", setAvTransportUriParams(url, didl)) != null

    fun play(): Boolean = action("Play", "<Speed>1</Speed>") != null
    fun pause(): Boolean = action("Pause", "") != null
    fun stop(): Boolean = action("Stop", "") != null

    fun seek(positionSec: Double): Boolean =
        action("Seek", "<Unit>REL_TIME</Unit><Target>${secToHms(positionSec)}</Target>") != null

    /** 返回 (pos秒, dur秒)；失败 null。 */
    fun getPositionInfo(): Pair<Double, Double>? {
        val body = action("GetPositionInfo", "") ?: return null
        return parsePositionInfo(body)
    }

    /** AVTransport 当前传输状态（PLAYING/STOPPED/...）；失败 null。 */
    fun getTransportInfo(): String? {
        val body = action("GetTransportInfo", "") ?: return null
        return parseTransportState(body)
    }

    /** 发一个 SOAP 动作；成功返回响应 body，失败返回 null 并写 lastError。 */
    private fun action(name: String, paramsXml: String): String? {
        val pin = pinned ?: run { lastError = "rejected non-LAN controlUrl"; return null }
        return runCatching {
            val conn = (pin.url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Host", pin.hostHeader)
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", soapAction(name))
                setRequestProperty("User-Agent", "AirSonic-DLNA")
                setRequestProperty("Connection", "close")
            }
            val payload = soapBody(name, paramsXml).toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val fault = parseSoapError(body)
            if (code !in 200..299 || fault != null) {
                lastError = fault ?: "HTTP $code"
                Log.w(TAG, "$name failed: $lastError")
                null
            } else body
        }.onFailure { lastError = "exc:${it.javaClass.simpleName}:${it.message}"; Log.e(TAG, "$name exc", it) }
            .getOrNull()
    }

    companion object { private const val TAG = "DlnaController" }
}