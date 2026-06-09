// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 极简 RTSP 客户端（AirSonic Phase 0 推流验证）。
 *
 * 在单条 TCP 连接上完成 ANNOUNCE/SETUP/RECORD 控制握手，随后该连接复用为
 * RTP-over-TCP 数据通道（交错传输）。与本地测试接收端 mini_airplay_receiver.py
 * 对齐。真 AppleTV 走 UDP+ALAC+FairPlay，此为打通端到端"出声"的最小实现，
 * 架构保持一致，便于后续替换编码/加密。
 *
 * 连接保持打开：RECORD 成功后，外部通过 [outputStream] 写入 RTP 交错包。
 */
class RtspClient(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 8000
) {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var cseq = 1
    private var session: String? = null

    /** RECORD 成功后用于写 RTP 交错数据。 */
    val outputStream: OutputStream
        get() = output ?: error("RTSP 未连接")

    data class RtspResponse(val status: Int, val headers: Map<String, String>)

    fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
    }

    /** ANNOUNCE：用 SDP 声明音频格式。
     *  @param alacCookieHex 非空时声明 ALAC（rtpmap=AppleLossless），并附 36B magic cookie；
     *                       为空则声明 L16（裸 PCM）。
     *  @param audioKeyHex 非空时附带自测加密密钥（仅本地接收端使用；真设备无此字段）。 */
    fun announce(
        sampleRate: Int,
        channels: Int,
        audioKeyHex: String? = null,
        alacCookieHex: String? = null
    ): RtspResponse {
        val sdp = buildString {
            append("v=0\r\n")
            append("o=AirSonic 0 0 IN IP4 $host\r\n")
            append("s=AirSonic\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            if (alacCookieHex != null) {
                // 真 AirPlay2 用 96 AppleLossless；frames/payload 等参数随 cookie 一并声明
                append("a=rtpmap:96 AppleLossless\r\n")
                append("a=airsonic-alac:$alacCookieHex\r\n")
            } else {
                append("a=rtpmap:96 L16/$sampleRate/$channels\r\n")
            }
            if (audioKeyHex != null) append("a=airsonic-key:$audioKeyHex\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return request("ANNOUNCE", mapOf("Content-Type" to "application/sdp"), sdp)
    }

    fun setup(): RtspResponse {
        val resp = request(
            "SETUP",
            mapOf("Transport" to "RTP/AVP/TCP;interleaved=0-1")
        )
        session = resp.headers["session"]
        return resp
    }

    fun record(): RtspResponse {
        val headers = mutableMapOf("Range" to "npt=0-")
        session?.let { headers["Session"] = it }
        return request("RECORD", headers)
    }

    fun teardown(): RtspResponse? = runCatching {
        val headers = mutableMapOf<String, String>()
        session?.let { headers["Session"] = it }
        request("TEARDOWN", headers)
    }.getOrNull()

    private fun request(
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null
    ): RtspResponse {
        val out = output ?: error("RTSP 未连接")
        val url = "rtsp://$host/stream"
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: ${cseq++}\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        if (body != null) sb.append("Content-Length: ${body.size}\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (body != null) out.write(body)
        out.flush()
        return readResponse()
    }

    private fun readResponse(): RtspResponse {
        val inp = input ?: error("RTSP 未连接")
        val headerBytes = ArrayList<Byte>()
        var match = 0
        while (true) {
            val b = inp.read()
            if (b == -1) break
            headerBytes.add(b.toByte())
            match = when {
                match == 0 && b == '\r'.code -> 1
                match == 1 && b == '\n'.code -> 2
                match == 2 && b == '\r'.code -> 3
                match == 3 && b == '\n'.code -> 4
                else -> 0
            }
            if (match == 4) break
        }
        val text = String(headerBytes.toByteArray(), Charsets.US_ASCII)
        val lines = text.split("\r\n")
        val status = Regex("RTSP/1\\.0\\s+(\\d+)").find(lines.firstOrNull() ?: "")
            ?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] =
                lines[i].substring(idx + 1).trim()
        }
        Log.d(TAG, "RTSP <= $status $headers")
        return RtspResponse(status, headers)
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    companion object {
        private const val TAG = "RtspClient"
    }
}