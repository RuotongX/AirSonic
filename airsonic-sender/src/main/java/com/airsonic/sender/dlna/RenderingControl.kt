// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import android.util.Log
import java.net.HttpURLConnection

/**
 * 对一个 RenderingControl controlUrl 发 SOAP 音量动作（SetVolume/GetVolume/SetMute）。
 * 自带 SOAP 发送（不复用 DlnaController.action，避免改动已验证的 AVTransport 投送路径）。
 * 全部动作 InstanceID=0、Channel=Master。
 */
class RenderingControlController(controlUrl: String) {
    var lastError: String = ""
        private set

    private val pinned: PinnedUrl? = pinLanUrl(controlUrl)

    /** 设置音量 0..100，成功返回 true。 */
    fun setVolume(pct: Int): Boolean {
        val v = pct.coerceIn(0, 100)
        return action("SetVolume", "<Channel>Master</Channel><DesiredVolume>$v</DesiredVolume>") != null
    }

    /** 读回当前音量 0..100；失败/不支持返回 null。 */
    fun getVolume(): Int? {
        val body = action("GetVolume", "<Channel>Master</Channel>") ?: return null
        return parseCurrentVolume(body)
    }

    /** 静音/取消静音，成功返回 true。 */
    fun setMute(muted: Boolean): Boolean =
        action("SetMute", "<Channel>Master</Channel><DesiredMute>${if (muted) 1 else 0}</DesiredMute>") != null

    /** 发一个 RenderingControl SOAP 动作；成功返回响应 body，失败 null 并写 lastError。 */
    private fun action(name: String, paramsXml: String): String? {
        val pin = pinned ?: run { lastError = "rejected non-LAN controlUrl"; return null }
        return runCatching {
            val conn = (pin.url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 5000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Host", pin.hostHeader)
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", soapAction(name, RENDERING_CONTROL))
                setRequestProperty("User-Agent", "AirSonic-DLNA")
                setRequestProperty("Connection", "close")
            }
            val payload = soapBody(name, paramsXml, RENDERING_CONTROL).toByteArray(Charsets.UTF_8)
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
        }.onFailure { lastError = "exc:$name:${it.javaClass.simpleName}:${it.message}"; Log.e(TAG, "$name exc", it) }
            .getOrNull()
    }

    companion object { private const val TAG = "RenderingControl" }
}
