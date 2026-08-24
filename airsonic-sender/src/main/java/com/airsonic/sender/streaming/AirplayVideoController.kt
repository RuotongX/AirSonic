// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import com.airsonic.sender.pairing.PairingHandshake
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

/**
 * AirPlay 视频播放控制（对齐 pyatv 真机抓包 + tvOS 26 play-queue 新流程）。
 *
 * tvOS 26 起废弃老的 `POST /play + /setProperty + /rate` 流程（全 200 但播放器转圈不拉流），
 * 必须改走 play-queue `/command` 流程（参照 pyatv PR #2846 / airplayv2.py `play_url`，tvOS 26.5 实测可用）：
 *   SETUP#1(基础会话,NTP计时,+sessionCorrelationUUID) → 事件通道 → RECORD
 *   → SETUP#2(streams: type=130 遥控流) 拿 streams[0].streamID
 *   → POST /command ×4：insertPlayQueueItem → setProperty(isInterestedInDateRange)
 *     → setProperty(actionAtItemEnd) → setRate(1.0)
 *
 * 回退策略：play() 先试 play-queue 新流程，任一步失败（SETUP#2 非 2xx / 响应无 streamID /
 * 任一命令非 2xx）即回退老 /play 流程。不做 /info features bit33(SupportsAirPlayVideoPlayQueue)
 * 预探测——多一次请求且 bit 语义随版本变动，直接尝试+回退更简单可靠。
 * 见 recon/AppleTV视频_实现蓝图.md 与 recon/pyatv_appletv_play_trace.log。
 */
class AirplayVideoController(
    private val host: String,
    private val handshake: PairingHandshake,
) {
    private var channel: HapEncryptedChannel? = null
    private var cseq = 0
    private val sessionUuid = UUID.randomUUID().toString().uppercase()
    /** 老流程 /play 的 X-Apple-Session-ID（新流程改用 sessionUuid，对齐 pyatv）。 */
    private val playSessionId = UUID.randomUUID().toString()
    /** rtsp:// URL 的 session id。 */
    private val rtspSessionId: Long = (Math.random() * 0xFFFFFFFFL).toLong() and 0xFFFFFFFFL
    /** 随机化的发送端 MAC（locally-administered）。
     *  Android 拿不到真实 Wi-Fi MAC；pyatv 同样用随机值，设备只要求格式合法。 */
    private val senderDeviceId = randomSenderDeviceId()
    private var localIp = "0.0.0.0"
    private var timingServer: AirplayTimingServer? = null
    private var eventChannel: AirplayEventChannel? = null
    private val dacpId = "0000000000000001"

    /** play-queue 遥控流的 streamID（SETUP#2 响应）；null=未建立。 */
    private var playQueueStreamId: Int? = null
    /** 当前会话是否走 tvOS 26 play-queue 流程（play() 成功后确定）。 */
    private var usePlayQueue = false

    var lastStatus: Int = -1
        private set
    var lastError: String = ""
        private set

    /** 接收端主动断开/网络死（feedback 保活连续失败）时回调一次。 */
    var onConnectionLost: (() -> Unit)? = null
    /** send() 全加锁：保活线程与 UI 命令共用一条加密 socket，请求-响应必须成对不被穿插。 */
    private val sendLock = Any()
    @Volatile private var keepaliveStop = false
    private var keepaliveThread: Thread? = null

    data class Resp(val status: Int, val body: ByteArray)

    /** 建立加密通道 + 完整视频会话(SETUP→事件通道→RECORD)。就绪后即可 play()。 */
    fun connect(): Boolean = runCatching {
        val key = handshake.sessionKey ?: return false
        localIp = runCatching { handshake.httpClient.rawSocket().localAddress.hostAddress }.getOrNull() ?: "0.0.0.0"
        val (input, output) = handshake.httpClient.detachStreams()
        channel = HapEncryptedChannel(input, output, key)
        setupVideoSession(key).also { if (it) startKeepalive() }
    }.onFailure { lastError = "connect exc:${it.message}"; Log.e(TAG, "connect failed", it) }.getOrDefault(false)

    /** 2s `POST /feedback` 保活（pyatv airplayv2 同款）：保持会话不被接收端回收；连续 2 次失败判定断线。 */
    private fun startKeepalive() {
        keepaliveStop = false
        keepaliveThread = thread(isDaemon = true, name = "airplay-feedback") {
            var failures = 0
            while (!keepaliveStop) {
                Thread.sleep(2000)
                if (keepaliveStop) break
                failures = if (feedback()) 0 else failures + 1
                if (failures >= 2 && !keepaliveStop) {
                    Log.w(TAG, "feedback 连续失败，判定接收端已断")
                    runCatching { onConnectionLost?.invoke() }
                    break
                }
            }
        }
    }

    private fun feedback(): Boolean =
        (send(
            method = "POST", uri = "/feedback", proto = "RTSP/1.0",
            body = ByteArray(0), contentType = null,
            headers = linkedMapOf(
                "CSeq" to "${cseq++}",
                "User-Agent" to COMMAND_USER_AGENT,
                "DACP-ID" to dacpId,
                "Active-Remote" to "1",
                "Client-Instance" to dacpId,
            ),
        )?.status ?: -1) in 200..299

    private fun setupVideoSession(key: ByteArray): Boolean {
        // 1) NTP 计时服务器（SETUP 必须声明可响应的 timingPort）
        val tsrv = AirplayTimingServer()
        val timingPort = runCatching { tsrv.start(0) }.getOrDefault(0)
        timingServer = tsrv

        // macOS 接收器首次 SETUP 要弹「允许隔空投放」等用户点（最长 15s），默认 5s 读超时必死
        val sock = runCatching { handshake.httpClient.rawSocket() }.getOrNull()
        val oldSoTimeout = runCatching { sock?.soTimeout }.getOrNull()
        runCatching { sock?.soTimeout = 20000 }

        // 2) SETUP#1（RTSP，无 streams）→ eventPort
        //    tvOS 26 新流程要求带 sessionCorrelationUUID（pyatv airplayv2 `_setup_base`）；
        //    老设备忽略该字段，故两条流程共用此 SETUP。
        val setup1: Map<String, Any?> = linkedMapOf(
            "deviceID" to senderDeviceId,
            "macAddress" to senderDeviceId,
            "sessionUUID" to sessionUuid,
            "sessionCorrelationUUID" to UUID.randomUUID().toString().uppercase(),
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
        val r1 = rtsp("SETUP", BPlist.encode(setup1), BPLIST_CONTENT_TYPE) ?: return false
        if (r1.status !in 200..299) { Log.w(TAG, "SETUP#1 -> ${r1.status}"); return false }
        runCatching { if (oldSoTimeout != null) sock?.soTimeout = oldSoTimeout }
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

    /** 播放：先试 tvOS 26 play-queue 新流程，失败回退老 /play 流程（回退策略见类注释）。需先 connect()。 */
    fun play(url: String, startPosition: Double = 0.0): Boolean {
        if (playViaPlayQueue(url, startPosition)) {
            usePlayQueue = true
            return true
        }
        Log.w(TAG, "play-queue 流程失败，回退老 /play 流程")
        usePlayQueue = false
        return playLegacy(url, startPosition)
    }

    /** 老流程：POST /play + /setProperty + /rate=1（/play 后默认暂停，必须 rate 才开播）。 */
    private fun playLegacy(url: String, startPosition: Double): Boolean {
        val r = httpReq("POST", "/play", buildPlayBody(url, startPosition), BPLIST_CONTENT_TYPE)
        if (r == null || r.status !in 200..299) return false
        httpReq("PUT", "/setProperty?isInterestedInDateRange",
            BPlist.encode(linkedMapOf<String, Any?>("value" to true)), BPLIST_CONTENT_TYPE)
        httpReq("PUT", "/setProperty?actionAtItemEnd",
            BPlist.encode(linkedMapOf<String, Any?>("value" to 0)), BPLIST_CONTENT_TYPE)
        rate(1)  // ← 开播(100% 速率)，否则停在暂停/转圈
        return true
    }

    /**
     * tvOS 26 play-queue 新流程（对齐 pyatv airplayv2 `play_url`）：
     * SETUP#2 申请遥控流拿 streamID，再依序发 4 条 /command。任一步失败返回 false（由 play() 回退）。
     * 注意：若 SETUP#2 已成功而命令失败，会话上会留一个遥控流，老流程回退仍可用（实测不冲突）。
     */
    private fun playViaPlayQueue(url: String, startPosition: Double): Boolean {
        val sid = setupPlayQueueStream() ?: return false
        playQueueStreamId = sid
        Log.i(TAG, "SETUP#2(streams) ✓ streamID=$sid")
        val itemUuid = UUID.randomUUID().toString().uppercase()
        val commands = listOf<Map<String, Any?>>(
            linkedMapOf("type" to "insertPlayQueueItem", "item" to linkedMapOf(
                "uuid" to itemUuid,
                "mediaType" to "file",
                "Content-Location" to url,
                "Start-Position-Seconds" to startPosition,
            )),
            linkedMapOf("type" to "setProperty", "value" to true,
                "property" to "isInterestedInDateRange",
                "item" to linkedMapOf("uuid" to itemUuid)),
            linkedMapOf("type" to "setProperty", "value" to 1, "property" to "actionAtItemEnd"),
            linkedMapOf("type" to "setRate", "rate" to 1.0),
        )
        for (cmd in commands) {
            val r = sendCommand(cmd) ?: return false
            if (r.status !in 200..299) { Log.w(TAG, "/command ${cmd["type"]} -> ${r.status}"); return false }
        }
        return true
    }

    /** SETUP#2：申请 type=130 遥控流（play-queue 命令通道），返回 streams[0].streamID；失败返回 null。 */
    private fun setupPlayQueueStream(): Int? {
        val body = BPlist.encode(linkedMapOf<String, Any?>("streams" to listOf(
            linkedMapOf<String, Any?>(
                "clientUUID" to UUID.randomUUID().toString().uppercase(),
                "clientTypeUUID" to REMOTE_CONTROL_CLIENT_TYPE,
                "channelID" to "$senderDeviceId-RCS-1",
                "controlType" to 1,
                "type" to 130,
            ))))
        val r = rtsp("SETUP", body, BPLIST_CONTENT_TYPE) ?: return null
        if (r.status !in 200..299) { Log.w(TAG, "SETUP#2(streams) -> ${r.status}"); return null }
        @Suppress("UNCHECKED_CAST")
        val pl = runCatching { BPlist.decode(r.body) as? Map<Any?, Any?> }.getOrNull() ?: return null
        val streams = pl["streams"] as? List<*> ?: return null
        @Suppress("UNCHECKED_CAST")
        val s0 = streams.firstOrNull() as? Map<Any?, Any?> ?: return null
        return (s0["streamID"] as? Number)?.toInt()
    }

    /**
     * POST /command（HTTP/1.1）：body 为双层 bplist {"params":{"data":<内层 bplist 命令>}}。
     * 头含 X-Apple-StreamID（**无连字符**，区别于老流程的 X-Apple-Stream-ID: 1）与 X-Apple-Session-ID；
     * 对齐 pyatv `RtspSession.exchange`：HTTP 请求同样带 CSeq/DACP-ID/Active-Remote/Client-Instance。
     */
    private fun sendCommand(command: Map<String, Any?>): Resp? {
        val sid = playQueueStreamId ?: return null
        return send(
            method = "POST",
            uri = "/command",
            proto = "HTTP/1.1",
            body = buildCommandBody(command),
            contentType = BPLIST_CONTENT_TYPE,
            headers = linkedMapOf(
                "CSeq" to "${cseq++}",
                "DACP-ID" to dacpId,
                "Active-Remote" to "1",
                "Client-Instance" to dacpId,
                "User-Agent" to COMMAND_USER_AGENT,
                "X-Apple-ProtocolVersion" to "1",
                "X-Apple-Session-ID" to sessionUuid,
                "X-Apple-StreamID" to "$sid",
            ),
        )
    }

    /** 播放速率：1=播放, 0=暂停。新流程走 setRate 命令；老流程 POST /rate?value=N.000000。 */
    fun rate(value: Int): Boolean =
        if (usePlayQueue && playQueueStreamId != null) {
            val r = sendCommand(linkedMapOf("type" to "setRate", "rate" to value.toDouble()))
            (r?.status ?: -1) in 200..299
        } else {
            (httpReq("POST", "/rate?value=$value.000000", ByteArray(0), null)?.status ?: -1) in 200..299
        }

    fun scrub(positionSec: Double): Boolean =
        (httpReq("POST", "/scrub?position=$positionSec", ByteArray(0), null)?.status ?: -1) in 200..299

    /**
     * 进度/时长查询。tvOS 26（play-queue 流程）下 /playback-info 恒 500，
     * 播放状态改由事件通道 playbackState 事件推送（当前未解析），故新流程下优雅降级返回 null。
     */
    fun playbackInfo(): Pair<Double, Double>? {
        if (usePlayQueue) return null
        val r = httpReq("GET", "/playback-info", ByteArray(0), null) ?: return null
        @Suppress("UNCHECKED_CAST")
        val pl = runCatching { BPlist.decode(r.body) as? Map<Any?, Any?> }.getOrNull() ?: return null
        val pos = (pl["position"] as? Number)?.toDouble() ?: 0.0
        val dur = (pl["duration"] as? Number)?.toDouble() ?: 0.0
        return pos to dur
    }

    fun stop(): Boolean =
        (httpReq("POST", "/stop", ByteArray(0), null)?.status ?: -1) in 200..299

    /** 音量实验 A：play-queue setProperty volume（0.0~1.0）。需新流程已建 streamID。 */
    fun setVolumeCommand(vol: Double): Boolean {
        if (playQueueStreamId == null) return false
        val r = sendCommand(linkedMapOf("type" to "setProperty", "value" to vol, "property" to "volume"))
        return (r?.status ?: -1) in 200..299
    }

    /** 音量实验 B：老 AirPlay1 风格 POST /volume?value=（0.0~1.0）。 */
    fun setVolumeHttp(vol: Double): Boolean =
        (httpReq("POST", "/volume?value=$vol", ByteArray(0), null)?.status ?: -1) in 200..299

    /** 音量实验 C：RTSP SET_PARAMETER volume（RAOP 风格 dB，-30..0，-144 静音）。 */
    fun setVolumeRtsp(db: Double): Boolean =
        (rtsp("SET_PARAMETER", "volume: $db\r\n".toByteArray(Charsets.US_ASCII), "text/parameters")?.status ?: -1) in 200..299

    fun close() {
        keepaliveStop = true
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
            "User-Agent" to COMMAND_USER_AGENT,
            "DACP-ID" to dacpId,
            "Active-Remote" to "1",
            "Client-Instance" to dacpId,
        ),
    )

    /** 老流程 HTTP/1.1 请求（/play /rate /scrub /stop /playback-info），带 X-Apple-* 头（无 CSeq，对齐 pyatv）。 */
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
        return synchronized(sendLock) {
            runCatching {
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
        /** play-queue 流程及 SETUP 的 User-Agent（pyatv COMMAND_USER_AGENT）。 */
        private const val COMMAND_USER_AGENT = "AirPlay/870.14.1"
        /** play-queue 遥控流的 clientTypeUUID（pyatv REMOTE_CONTROL_CLIENT_TYPE）。 */
        private const val REMOTE_CONTROL_CLIENT_TYPE = "A6B27562-B43A-4F2D-B75F-82391E250194"
        private const val BPLIST_CONTENT_TYPE = "application/x-apple-binary-plist"

        /** /command 体：{"params":{"data":<内层 bplist 命令>}}，双层 bplist 嵌套（对齐 pyatv `_send_url_command`）。 */
        fun buildCommandBody(command: Map<String, Any?>): ByteArray =
            BPlist.encode(linkedMapOf<String, Any?>(
                "params" to linkedMapOf<String, Any?>("data" to BPlist.encode(command))
            ))

        /** 生成 locally-administered 随机 MAC（首字节 (b & 0xFC) | 0x02），对齐 pyatv airplayv2。 */
        private fun randomSenderDeviceId(): String {
            val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
            b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()
            return b.joinToString(":") { "%02X".format(it) }
        }

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
