package com.airsonic.sender.dlna

import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** 对一个 AVTransport controlUrl 发 SOAP 控制动作。 */
class DlnaController(private val controlUrl: String) {
    var lastError: String = ""
        private set

    /**
     * 防 SSRF：controlUrl 来自 SSDP 发现的设备描述（未信任源），恶意设备可把它指向
     * 回环/链路本地(169.254 云元数据)/任意内网主机。DLNA 只在局域网用 → 只允许
     * http(s) + RFC1918 站点本地地址（站点本地天然排除回环与链路本地）。构造时校验一次。
     */
    private val allowed: Boolean = runCatching {
        val u = URL(controlUrl)
        u.protocol.lowercase() in listOf("http", "https") &&
            InetAddress.getByName(u.host).isSiteLocalAddress
    }.getOrDefault(false)

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

    /** 发一个 SOAP 动作；成功返回响应 body，失败返回 null 并写 lastError。 */
    private fun action(name: String, paramsXml: String): String? {
        if (!allowed) { lastError = "rejected non-LAN controlUrl"; return null }
        return runCatching {
            val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 5000
                doOutput = true
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
