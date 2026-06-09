// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import com.airsonic.sender.pairing.PairingHandshake
import java.util.UUID

/**
 * AirPlay 视频播放控制（对齐 pyatv 真机抓包）。
 *
 * 关键：Apple TV 对**裸 /play 不响应**，必须先走完整会话序列：
 *   pair-verify(已完成) → SETUP(拿 eventPort,NTP计时) → 事件通道 → RECORD → POST /play
 * 其中 SETUP/RECORD 走 RTSP/1.0(带 DACP 头)，/play 走 HTTP/1.1(带 X-Apple-ProtocolVersion/Session-ID/Stream-ID)。
 * 见 recon/AppleTV视频_实现蓝图.md 与 recon/pyatv_appletv_play_trace.log。
 */
class AirplayVideoController(
    private val host: String,
    private val handshake: PairingHandshake,
) {
    private var channel: HapEncryptedChannel? = null
    private var cseq = 0
    private val sessionUuid = UUID.randomUUID().toString().uppercase()
    /** /play 的 X-Apple-Session-ID。 */
    private val playSessionId = UUID.randomUUID().toString()
    /** rtsp:// URL 的 session id。 */
    private val rtspSessionId: Long = (Math.random() * 0xFFFFFFFFL).toLong() and 0xFFFFFFFFL
    private var localIp = "0.0.0.0"
    private var timingServer: AirplayTimingServer? = null
    private var eventChannel: AirplayEventChannel? = null
    private val dacpId = "0000000000000001"

    var lastStatus: Int = -1
        private set
    var lastError: String = ""
        private set

    data class Resp(val status: Int, val body: ByteArray)

    /** 建立加密通道 + 完整视频会话(SETUP→事件通道→RECORD)。就绪后即可 play()。 */
    fun connect(): Boolean = runCatching {
        val key = handshake.sessionKey ?: return false
        localIp = runCatching { handshake.httpClient.rawSocket().localAddress.hostAddress }.getOrNull() ?: "0.0.0.0"
        val (input, output) = handshake.httpClient.detachStreams()
        channel = HapEncryptedChannel(input, output, key)
        setupVideoSession(key)
    }.onFailure { lastError = "connect exc:${it.message}"; Log.e(TAG, "connect failed", it) }.getOrDefault(false)

    private fun setupVideoSession(key: ByteArray): Boolean {
        // 1) NTP 计时服务器（SETUP 必须声明可响应的 timingPort）
        val tsrv = AirplayTimingServer()
        val timingPort = runCatching { tsrv.start(0) }.getOrDefault(0)
        timingServer = tsrv

        // 2) SETUP#1（RTSP，无 streams）→ eventPort
        val setup1: Map<String, Any?> = linkedMapOf(
            "deviceID" to "AA:BB:CC:DD:EE:FF",
            "macAddress" to "AA:BB:CC:DD:EE:FF",
            "sessionUUID" to sessionUuid,
            "isMultiSelectAirPlay" to true,
            "timingProtocol" to "NTP",
            "timingPort" to timingPort,
            "name" to "AirSonic",
            "model" to "iPhone14,3",
            "osName" to "iPhone OS",
            "osVersion" to "16.5",
            "osBuildVersion" to "20F66",
            "sourceVersion" to "690.7.1",
            "senderSupportsRelay" to true,
            "statsCollectionEnabled" to false,
            "groupContainsGroupLeader" to false,
        )
        val r1 = rtsp("SETUP", BPlist.encode(setup1), "application/x-apple-binary-plist") ?: return false
        if (r1.status !in 200..299) { Log.w(TAG, "SETUP#1 -> ${r1.status}"); return false }
        @Suppress("UNCHECKED_CAST")
        val pl1 = runCatching { BPlist.decode(r1.body) as? Map<Any?, Any?> }.getOrNull()
        val eventPort = (pl1?.get("eventPort") as? Number)?.toInt() ?: 0
        Log.i(TAG, "SETUP#1 ✓ eventPort=$eventPort")

        // 3) 事件通道（设备会在此推 POST /command，不建则扣留后续响应）
        if (eventPort > 0) {
            val evt = AirplayEventChannel(host, eventPort, key) { Log.i(TAG, "evt: $it") }
            if (evt.start()) eventChannel = evt
        }

        // 4) RECORD（realtime 下可能无响应，超时正常）
        rtsp("RECORD", null, null)
        return true
    }

    /** POST /play + 启动序列。需先 connect()。
     *  关键：/play 后视频默认**暂停**，必须发 /rate=1.000000 才开播(否则黑屏转圈)。对齐 pyatv。 */
    fun play(url: String, startPosition: Double = 0.0): Boolean {
        val r = httpReq("POST", "/play", buildPlayBody(url, startPosition), "application/x-apple-binary-plist")
        if (r == null || r.status !in 200..299) return false
        httpReq("PUT", "/setProperty?isInterestedInDateRange",
            BPlist.encode(linkedMapOf<String, Any?>("value" to true)), "application/x-apple-binary-plist")
        httpReq("PUT", "/setProperty?actionAtItemEnd",
            BPlist.encode(linkedMapOf<String, Any?>("value" to 0)), "application/x-apple-binary-plist")
        rate(1)  // ← 开播(100% 速率)，否则停在暂停/转圈
        return true
    }

    /** 播放速率：1=播放, 0=暂停。pyatv 用浮点格式 value=N.000000。 */
    fun rate(value: Int): Boolean =
        (httpReq("POST", "/rate?value=$value.000000", ByteArray(0), null)?.status ?: -1) in 200..299

    fun scrub(positionSec: Double): Boolean =
        (httpReq("POST", "/scrub?position=$positionSec", ByteArray(0), null)?.status ?: -1) in 200..299

    fun playbackInfo(): Pair<Double, Double>? {
        val r = httpReq("GET", "/playback-info", ByteArray(0), null) ?: return null
        @Suppress("UNCHECKED_CAST")
        val pl = runCatching { BPlist.decode(r.body) as? Map<Any?, Any?> }.getOrNull() ?: return null
        val pos = (pl["position"] as? Number)?.toDouble() ?: 0.0
        val dur = (pl["duration"] as? Number)?.toDouble() ?: 0.0
        return pos to dur
    }

    fun stop(): Boolean =
        (httpReq("POST", "/stop", ByteArray(0), null)?.status ?: -1) in 200..299

    fun close() {
        runCatching { eventChannel?.stop() }; eventChannel = null
        runCatching { timingServer?.stop() }; timingServer = null
        channel = null
        runCatching { handshake.httpClient.close() }
    }

    /** RTSP/1.0 请求（SETUP/RECORD），带 DACP/Active-Remote/Client-Instance + rtsp:// URL。 */
    private fun rtsp(method: String, body: ByteArray?, contentType: String?): Resp? = send(
        method = method,
        uri = "rtsp://$localIp/$rtspSessionId",
        proto = "RTSP/1.0",
        body = body ?: ByteArray(0),
        contentType = contentType,
        headers = linkedMapOf(
            "CSeq" to "${cseq++}",
            "User-Agent" to "AirPlay/550.10",
            "DACP-ID" to dacpId,
            "Active-Remote" to "1",
            "Client-Instance" to dacpId,
        ),
    )

    /** HTTP/1.1 请求（/play /rate /scrub /stop /playback-info），带 X-Apple-* 头（无 CSeq，对齐 pyatv）。 */
    private fun httpReq(method: String, path: String, body: ByteArray, contentType: String?): Resp? = send(
        method = method,
        uri = path,
        proto = "HTTP/1.1",
        body = body,
        contentType = contentType,
        headers = linkedMapOf(
            "User-Agent" to "AirPlay/550.10",
            "X-Apple-Session-ID" to playSessionId,
            "X-Apple-ProtocolVersion" to "1",
            "X-Apple-Stream-ID" to "1",
        ),
    )

    private fun send(method: String, uri: String, proto: String, body: ByteArray, contentType: String?, headers: Map<String, String>): Resp? {
        val ch = channel ?: return null
        return runCatching {
            val sb = StringBuilder()
            sb.append("$method $uri $proto\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            if (body.isNotEmpty()) {
                sb.append("Content-Length: ${body.size}\r\n")
                if (contentType != null) sb.append("Content-Type: $contentType\r\n")
            }
            sb.append("\r\n")
            ch.sendEncrypted(sb.toString().toByteArray(Charsets.US_ASCII) + body)
            val r = parse(ch.recvResponse())
            lastStatus = r.status
            lastError = "status=${r.status}"
            Log.i(TAG, "$method $uri -> ${r.status} (body ${r.body.size}B)")
            r
        }.onFailure { lastError = "exc:${it.javaClass.simpleName}:${it.message}"; Log.e(TAG, "video req $uri failed", it) }.getOrNull()
    }

    private fun parse(raw: ByteArray): Resp {
        var sep = -1
        for (i in 0..raw.size - 4) {
            if (raw[i] == 13.toByte() && raw[i+1] == 10.toByte() && raw[i+2] == 13.toByte() && raw[i+3] == 10.toByte()) { sep = i; break }
        }
        val headerStr = String(raw, 0, if (sep >= 0) sep else raw.size, Charsets.ISO_8859_1)
        val status = Regex("(?:RTSP|HTTP)/1\\.[01]\\s+(\\d{3})").find(headerStr)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val bodyArr = if (sep >= 0 && sep + 4 <= raw.size) raw.copyOfRange(sep + 4, raw.size) else ByteArray(0)
        return Resp(status, bodyArr)
    }

    companion object {
        private const val TAG = "AirplayVideoController"
        /** /play 体——对照 pyatv 抓包字段集。 */
        fun buildPlayBody(url: String, startPosition: Double): ByteArray =
            BPlist.encode(linkedMapOf<String, Any?>(
                "Content-Location" to url,
                "Start-Position-Seconds" to startPosition,
                "SenderMACAddress" to "AA:BB:CC:DD:EE:FF",
                "model" to "iPhone14,3",
                "osBuildVersion" to "20F66",
                "clientBundleID" to "com.airsonic.demo",
                "clientProcName" to "AirSonic",
                "mediaType" to "file",
                "streamType" to 1,
                "rate" to 1.0,
                "uuid" to UUID.randomUUID().toString(),
                "volume" to 1.0,
                "mightSupportStorePastisKeyRequests" to true,
                "authMs" to 0, "bonjourMs" to 0, "connectMs" to 0, "infoMs" to 0,
                "postAuthMs" to 0, "secureConnectionMs" to 0,
                "playbackRestrictions" to 0, "referenceRestrictions" to 3,
            ))
    }
}