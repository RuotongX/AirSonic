// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import com.airsonic.sender.pairing.CryptoPrimitives
import com.airsonic.sender.pairing.PairingHandshake
import java.io.BufferedInputStream
import java.io.FileDescriptor
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

/**
 * AirPlay2 加密 RTSP 会话：在 transient pair-setup 完成后，复用同一 TCP 连接，
 * 通过 [HapEncryptedChannel] 加密执行 SETUP#1 / SETUP#2 / RECORD 握手。
 *
 * 已在真机 HomePod 全程验证通过：
 *   SETUP#1（无 streams）→ 200，返回 eventPort + PTP timingPeerInfo
 *   SETUP#2（含 streams[]）→ 200，返回 dataPort + controlPort（音频端口分配成功）
 *
 * 用法：
 *   val hs = PairingHandshake(host, port)
 *   hs.pairSetup(pin = "3939", onStep = {...}, transient = true)   // 必须先成功
 *   val sess = AirplayStreamSession(host, hs)
 *   val result = sess.setup(onStep = {...})                         // 拿到 dataPort
 */
class AirplayStreamSession(
    private val host: String,
    private val handshake: PairingHandshake
) {
    data class StreamResult(
        val eventPort: Int,
        val dataPort: Int,
        val controlPort: Int,
        val audioKey: ByteArray,      // 32B 音频共享密钥（SETUP#2 中我方生成的 shk）
        val streamConnectionId: Long
    )

    sealed class Step {
        data class Info(val message: String) : Step()
        data class Success(val message: String) : Step()
        data class Failure(val message: String, val cause: Throwable? = null) : Step()
    }

    private var channel: HapEncryptedChannel? = null
    private var cseq = 10
    private val sessionUuid = UUID.randomUUID().toString().uppercase()
    private val groupUuid = UUID.randomUUID().toString().uppercase()
    /**
     * 数字 session_id（对齐 pyatv：randrange(2^32)）。
     * pyatv 的 RTSP URL = rtsp://ip/{session_id}，且音频 RTP 包的 ssrc = 同一个 session_id。
     * 之前 URL 用 UUID 字符串、ssrc 用固定 0x6A6F6E65，两者不一致 → HomePod 无法把
     * 音频包关联到会话 → 丢弃 → 停在 Paused 不出声。现统一为同一数字。
     */
    private val sessionId: Long = (Math.random() * 0xFFFFFFFFL).toLong() and 0xFFFFFFFFL
    /** 发送端 local IP，用于 RTSP URL（对齐 pyatv）。在通道建立后赋值。 */
    private var localIp: String = "0.0.0.0"

    // ---- AirPlay 2 timing 基础设施（真设备出声必需）----
    private var timingServer: AirplayTimingServer? = null
    private var syncSender: AirplaySyncSender? = null
    /** AirPlay 2 事件通道（独立 TCP 连接到 device:eventPort，吸收设备 POST /command）。 */
    private var eventChannel: AirplayEventChannel? = null
    /**
     * 控制 socket（对齐 pyatv 单一控制 socket 模型）：在 SETUP#2 之前创建并绑定端口，
     * 把【实际绑定端口】声明为 SETUP#2 的 controlPort；随后 Sync 发送器复用它（sync 包从此端口发出），
     * 并起接收线程吸收设备发来的控制/retransmit 消息。之前声明固定 6001 但实际从临时端口发 + 不监听，
     * HomePod 可能因控制端口不一致而不渲染。
     */
    private var controlSocket: DatagramSocket? = null
    /** 当前活动的 onStep，供后台线程（timing server）上报事件到 UI。 */
    @Volatile private var activeOnStep: ((Step) -> Unit)? = null
    private var feedbackThread: Thread? = null
    @Volatile private var feedbackRunning = false

    /** 完成 SETUP#1 + #2，返回音频端口信息。要求 handshake 已 transient 配对成功。 */
    /** SETUP#2 是否用 ALAC（true=ct2/audioFormat 1<<18；false=PCM L16）。仅收 ALAC 的设备(如 Sonos)置 true。 */
    private var useAlac = false

    fun setup(
        deviceId: String = "AA:BB:CC:DD:EE:FF",
        useAlac: Boolean = false,
        onStep: (Step) -> Unit
    ): StreamResult? {
        this.useAlac = useAlac
        return try {
            val key = handshake.sessionKey
                ?: run { onStep(Step.Failure("尚未完成 transient 配对，sessionKey 为空")); return null }

            // 复用配对的同一条 TCP 连接，建立加密通道
            val (input, output) = handshake.httpClient.detachStreams()
            val ch = HapEncryptedChannel(input, output, key)
            channel = ch
            // 捕获发送端 local IP（对齐 pyatv：RTSP URL = rtsp://{local_ip}/{session_id}）
            localIp = runCatching {
                handshake.httpClient.rawSocket().localAddress.hostAddress
            }.getOrNull() ?: "0.0.0.0"
            onStep(Step.Info("HAP 加密通道已建立（复用配对连接，K=${key.size}B）"))

            // ---- 启动计时服务器（SETUP#1 之前，必须在 timingPort 上可响应）----
            activeOnStep = onStep
            val tsrv = AirplayTimingServer()
            val timingPort = runCatching {
                tsrv.start(0) { msg -> activeOnStep?.invoke(Step.Info(msg)) }
            }.getOrDefault(0)
            timingServer = tsrv
            onStep(Step.Info("计时服务器已启动（NTP timingPort=$timingPort）"))

            // ---- SETUP#1（无 streams）----
            val setup1: Map<String, Any?> = linkedMapOf(
                "deviceID" to deviceId,
                "macAddress" to deviceId,
                "sessionUUID" to sessionUuid,
                "groupUUID" to groupUuid,
                "isMultiSelectAirPlay" to true,
                "timingProtocol" to "NTP",          // PTP→NTP：与 pyatv 工作路径一致
                "timingPort" to timingPort,         // 真实计时端口（不再为 0）
                "name" to "AirSonic",
                "model" to "iPhone14,2",
                "osName" to "iPhone OS",
                "osVersion" to "17.0",
                "osBuildVersion" to "21A329",
                "sourceVersion" to "950.7.1"
            )
            onStep(Step.Info("发送 SETUP#1（NTP timing，无 streams）"))
            val resp1 = rtspEncrypted(ch, "SETUP", BPlist.encode(setup1))
            if (resp1.status !in 200..299) {
                onStep(Step.Failure("SETUP#1 状态异常: ${resp1.status}")); return null
            }
            @Suppress("UNCHECKED_CAST")
            val plist1 = BPlist.decode(resp1.body) as? Map<Any?, Any?>
            val eventPort = (plist1?.get("eventPort") as? Long)?.toInt()
                ?: (plist1?.get("eventPort") as? Int) ?: 0
            onStep(Step.Success("SETUP#1 ✓ eventPort=$eventPort"))

            // ---- 事件通道（关键！对齐 pyatv）----
            // SETUP#1 后立即另开一条 TCP 连接到 device:eventPort，设备会在此推送
            // POST /command；不建此通道设备会扣留 RECORD 响应 → 停 Paused 不出声。
            if (eventPort > 0) {
                val evt = AirplayEventChannel(host, eventPort, key) { msg ->
                    activeOnStep?.invoke(Step.Info("  · $msg"))
                }
                if (evt.start()) eventChannel = evt
            }

            // ---- SETUP#2（音频 stream：realtime + ALAC）----
            // 关键（对齐 pyatv）：shk = 事件通道 out_key 的前 32 字节
            //   = HKDF(SRP sessionKey, salt="Events-Salt", info="Events-Write-Encryption-Key")
            // 这个 key 同时用于音频 RTP 包加密；二者必须一致，否则设备解密失败→无声。
            // 之前用 randomBytes(32)，音频又用另一套 key → 设备全丢 → 完全没声。
            val shk = AirplayEventChannel.deriveAudioSharedKey(key)
            // 对齐 pyatv（实测 --debug 日志确认）：streamConnectionID == RTSP URL 的 session_id。
            // 之前用独立随机数 → HomePod 无法把 stream 关联到会话 → RECORD 无响应/FLUSH 500。
            val streamConnId = sessionId
            // 对齐 pyatv：先建控制 socket 绑临时端口，把实际端口声明为 controlPort。
            val cs = DatagramSocket()
            controlSocket = cs
            val ourControlPort = cs.localPort
            onStep(Step.Info("控制 socket 绑定本地端口 $ourControlPort（将声明为 controlPort）"))
            val stream: Map<String, Any?> = linkedMapOf(
                "type" to 96,                 // 96=realtime
                "ct" to (if (useAlac) 2 else 1),                            // 2=ALAC(Sonos 等) / 1=PCM
                "audioFormat" to (if (useAlac) (1 shl 18) else (1 shl 11)), // ALAC 44100/16/2 / PCM L16
                "audioMode" to "default",     // 对齐 pyatv
                "isMedia" to true,            // 对齐 pyatv
                "sr" to 44100,                // 采样率（对齐 pyatv）
                "spf" to 352,
                "shk" to shk,
                "latencyMin" to 11025,
                "latencyMax" to 88200,
                "controlPort" to ourControlPort,
                "supportsDynamicStreamID" to false,  // 对齐 pyatv（实测 bplist 为 \x08=false）
                "streamConnectionID" to streamConnId
            )
            val setup2: Map<String, Any?> = linkedMapOf("streams" to listOf(stream))
            onStep(Step.Info("发送 SETUP#2（realtime ${if (useAlac) "ALAC" else "PCM/L16"}，shk=32B）"))
            val resp2 = rtspEncrypted(ch, "SETUP", BPlist.encode(setup2))
            if (resp2.status !in 200..299) {
                onStep(Step.Failure("SETUP#2 状态异常: ${resp2.status}")); return null
            }
            @Suppress("UNCHECKED_CAST")
            val plist2 = BPlist.decode(resp2.body) as? Map<Any?, Any?>
            @Suppress("UNCHECKED_CAST")
            val streams = plist2?.get("streams") as? List<Map<Any?, Any?>>
            val s0 = streams?.firstOrNull()
                ?: run { onStep(Step.Failure("SETUP#2 响应无 streams: keys=${plist2?.keys}")); return null }
            val dataPort = (s0["dataPort"] as? Long)?.toInt() ?: (s0["dataPort"] as? Int) ?: 0
            val controlPort = (s0["controlPort"] as? Long)?.toInt() ?: (s0["controlPort"] as? Int) ?: 0
            onStep(Step.Success("SETUP#2 ✓ dataPort=$dataPort, controlPort=$controlPort（音频端口分配成功！）"))

            // 注：移除 SETPEERS（pyatv NTP timing 路径不发；HomePod 静音排障期间对齐 pyatv）。
            StreamResult(eventPort, dataPort, controlPort, shk, streamConnId)
        } catch (t: Throwable) {
            Log.e(TAG, "setup error", t)
            onStep(Step.Failure("SETUP 异常: ${t.message}", t))
            null
        }
    }

    /**
     * 发送 RECORD。注意：realtime 流（type=96）通常不依赖 RECORD 响应，
     * 真机 HomePod 对此路径不回包（实测超时）。因此这里发送后短等，
     * 超时视为正常（可继续推流），不作为失败。
     *
     * @param seq 首个 RTP 包的序号（与后续推流一致）
     * @param rtptime 首个 RTP 包的时间戳（与后续推流一致）
     */
    fun record(seq: Int = 0, rtptime: Int = 0, onStep: (Step) -> Unit): Boolean {
        val ch = channel ?: run { onStep(Step.Failure("通道未建立")); return false }
        val seqU = seq and 0xFFFF
        val rtpU = rtptime.toLong() and 0xFFFFFFFFL   // 无符号 32 位
        return try {
            onStep(Step.Info("发送 RECORD"))
            val resp = runCatching {
                // 对齐 pyatv：HomePod 的 RECORD 是纯净请求，不带 Range/RTP-Info，
                // 否则设备不响应（超时 5s）。RTP 起点信息由 FLUSH 携带。
                rtspEncrypted(ch, "RECORD", null)
            }.getOrNull()
            when {
                resp == null -> {
                    onStep(Step.Success("RECORD 已发送（无响应，realtime 正常）✓"))
                    true
                }
                resp.status in 200..299 -> {
                    onStep(Step.Success("RECORD ✓ ${resp.status}")); true
                }
                else -> {
                    onStep(Step.Info("RECORD 返回 ${resp.status}（realtime 可忽略，继续）")); true
                }
            }
        } catch (t: Throwable) {
            // realtime 下 RECORD 超时不致命
            onStep(Step.Info("RECORD 无响应（realtime 正常，继续推流）"))
            true
        }
    }

    /**
     * 发送 FLUSH（arming 播放锚点）。对齐 pyatv send_audio 的 record→flush→volume 顺序。
     * FLUSH 携带 RTP-Info（seq;rtptime）告诉接收端从哪里开始播放。
     */
    fun flush(seq: Int = 0, rtptime: Int = 0, onStep: (Step) -> Unit): Boolean {
        val ch = channel ?: run { onStep(Step.Failure("通道未建立")); return false }
        val seqU = seq and 0xFFFF
        val rtpU = rtptime.toLong() and 0xFFFFFFFFL
        return try {
            onStep(Step.Info("发送 FLUSH（RTP-Info: seq=$seqU;rtptime=$rtpU）"))
            val resp = runCatching {
                // 对齐 pyatv：FLUSH 携带 Range + Session + RTP-Info，缺 Range/Session 时
                // HomePod 返回 500。这是 arming 播放锚点的关键消息。
                rtspEncrypted(
                    ch, "FLUSH", null,
                    extraHeaders = mapOf(
                        "Range" to "npt=0-",
                        "Session" to "0",
                        "RTP-Info" to "seq=$seqU;rtptime=$rtpU"
                    )
                )
            }.getOrNull()
            when {
                resp == null -> { onStep(Step.Info("FLUSH 无响应（继续）")); true }
                resp.status in 200..299 -> { onStep(Step.Success("FLUSH ✓ ${resp.status}")); true }
                else -> { onStep(Step.Info("FLUSH 返回 ${resp.status}（继续）")); true }
            }
        } catch (t: Throwable) {
            onStep(Step.Info("FLUSH 异常: ${t.message}（继续）")); true
        }
    }

    /**
     * 发送 SET_PARAMETER progress（对齐 pyatv：progress: start/current/end）。
     * pyatv 在 volume 之后、RECORD 之前发送，告知设备播放进度区间。
     * @param start 起始 rtptime（首包 ts）
     * @param end 结束 rtptime（start + 总样本数；未知时给一个较大值）
     */
    fun sendProgress(start: Int, current: Int, end: Int, onStep: (Step) -> Unit): Boolean {
        val ch = channel ?: run { onStep(Step.Failure("通道未建立")); return false }
        val s = start.toLong() and 0xFFFFFFFFL
        val c = current.toLong() and 0xFFFFFFFFL
        val e = end.toLong() and 0xFFFFFFFFL
        return try {
            val body = "progress: $s/$c/$e\r\n".toByteArray(Charsets.US_ASCII)
            onStep(Step.Info("发送 SET_PARAMETER progress=$s/$c/$e"))
            val resp = runCatching {
                rtspEncrypted(ch, "SET_PARAMETER", body, contentType = "text/parameters")
            }.getOrNull()
            when {
                resp == null -> { onStep(Step.Info("progress 无响应（继续）")); true }
                resp.status in 200..299 -> { onStep(Step.Success("progress ✓ ${resp.status}")); true }
                else -> { onStep(Step.Info("progress 返回 ${resp.status}（继续）")); true }
            }
        } catch (t: Throwable) {
            onStep(Step.Info("progress 异常: ${t.message}（继续）")); true
        }
    }

    /**
     * 发送 SET_PARAMETER volume（关键！真 AirPlay 设备默认静音 -144dB，
     * 不设音量即使解码正常也无声）。对齐 pyatv set_parameter("volume", value)。
     * @param volume AirPlay 音量：0.0=最大，-30.0~0.0 常用，-144.0=静音
     */
    fun setVolume(volume: Double = -15.0, onStep: (Step) -> Unit): Boolean {
        val ch = channel ?: run { onStep(Step.Failure("通道未建立")); return false }
        return try {
            val body = "volume: $volume\r\n".toByteArray(Charsets.US_ASCII)
            onStep(Step.Info("发送 SET_PARAMETER volume=$volume（解除静音）"))
            val resp = runCatching {
                rtspEncrypted(ch, "SET_PARAMETER", body, contentType = "text/parameters")
            }.getOrNull()
            when {
                resp == null -> { onStep(Step.Info("volume 无响应（继续）")); true }
                resp.status in 200..299 -> { onStep(Step.Success("volume ✓ ${resp.status}（已解除静音）")); true }
                else -> { onStep(Step.Info("volume 返回 ${resp.status}（继续）")); true }
            }
        } catch (t: Throwable) {
            onStep(Step.Info("volume 异常: ${t.message}（继续）")); true
        }
    }

    /**
     * 端到端音频出声：SETUP#2 拿到 [result] 后调用。
     *   1) 生成随机首包 seq/rtptime
     *   2) 发送 RECORD（RTP-Info 与首包一致）
     *   3) 解码本地文件 → ALAC 编码 → ChaCha20-Poly1305 加密 → UDP 推到 result.dataPort
     *
     * 音频密钥与 nonce 约定见 [EncryptedRtpAudioSender] 类注释（仍是占位算法，
     * 真设备最终出声需 Wireshark 抓包校准）。本方法先把"解码→编码→加密→UDP 推流"
     * 管道完整接通，把不确定性收敛到密钥/nonce 单点。
     *
     * @param fd 本地音频文件描述符（mp3/m4a/wav 等）
     * @param realtimePacing true=按采样率节流推送（贴近真设备）；false=最快推送（自测）
     */
    fun streamAudio(
        result: StreamResult,
        fd: FileDescriptor,
        realtimePacing: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onStep: (Step) -> Unit
    ): Boolean {
        val socket = DatagramSocket()
        return try {
            activeOnStep = onStep
            val target = InetAddress.getByName(host)
            val framesPerPacket = 352

            // 1) 共享时钟：RTP 时间轴单一事实来源（RECORD / SyncPacket / 音频包共用）
            //    注意：先创建，但锚定(reset)推迟到"推流即将开始"时刻，避免 RECORD/volume
            //    的 RTSP 往返耗时导致 RTP 时间轴与设备播放墙钟错位（首包被判过期丢弃）。
            val clock = AirplayClock(sampleRate = 44100, channels = 2)
            val seq0 = CryptoPrimitives.randomBytes(2)
                .fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) } and 0xFFFF

            // 2) 启动 Sync 发送器（每 1s 一个 SyncPacket → 设备 controlPort）
            //    这是真设备出声的关键锚点：缺它接收端 anchorRTPTimestamp 为空 → 静音
            if (result.controlPort > 0) {
                val ss = AirplaySyncSender(clock, host, result.controlPort,
                    externalSocket = controlSocket)   // 复用 SETUP#2 声明的控制 socket（对齐 pyatv）
                runCatching { ss.start() }
                    .onSuccess { syncSender = ss; onStep(Step.Info("Sync 发送器已启动 → controlPort=${result.controlPort}（1s 周期锚点）")) }
                    .onFailure { onStep(Step.Info("Sync 启动失败: ${it.message}（继续）")) }
            } else {
                onStep(Step.Info("无设备 controlPort，跳过 Sync（可能静音）"))
            }

            // 3) 启动 feedback 心跳（每 2s POST /feedback，保活连接，对齐 pyatv）
            startFeedback(onStep)

            // 4) 严格对齐 pyatv send_audio 顺序：reset(仅一次) → RECORD → FLUSH → volume → 音频包。
            //    ap2-receiver 已逐字节证明我们的音频包正确，剩余差异在控制编排：
            //    pyatv 不发 progress、不发 SETPEERS、只 reset 一次。这里全部对齐，杜绝 HomePod 挑剔点。
            clock.reset()
            val rtptime0 = clock.rtptime32
            record(seq = seq0, rtptime = rtptime0, onStep = onStep)
            flush(seq = seq0, rtptime = rtptime0, onStep = onStep)
            setVolume(volume = -15.0, onStep = onStep)

            // 5) 解码 → PCM/L16(大端) → 加密 → UDP（对齐 pyatv 已验证路径）
            onStep(Step.Info("开始推流音频到 dataPort=${result.dataPort}（${if (realtimePacing) "realtime 节流" else "最快"}），首包 ts=$rtptime0"))
            val decoder = LocalAudioDecoder()
            var sender: EncryptedRtpAudioSender? = null
            var sampleRate = 44100
            var channels = 2
            var lastLoggedKB = 0L

            decoder.decode(
                fd = fd,
                onFormat = { fmt ->
                    sampleRate = fmt.sampleRate
                    channels = fmt.channels
                    sender = EncryptedRtpAudioSender(
                        audioKey = result.audioKey,
                        ssrc = sessionId.toInt(),   // 对齐 pyatv：ssrc = RTSP session_id（关键！）
                        alac = if (useAlac) AlacEncoder(channels = fmt.channels) else null,  // Sonos 等→ALAC；否则 L16 PCM
                        channels = fmt.channels,
                        initialSeq = seq0,
                        initialTimestamp = rtptime0,
                        clock = clock          // 共享时钟：包 timestamp 由时钟驱动
                    )
                    onStep(Step.Info("音频格式 sr=${fmt.sampleRate} ch=${fmt.channels}，${if (useAlac) "ALAC" else "PCM/L16"} 加密推流就绪"))
                },
                onPcm = { chunk ->
                    sender?.sendOverUdp(
                        socket = socket,
                        target = target,
                        port = result.dataPort,
                        pcm = chunk,
                        framesPerPacket = framesPerPacket,
                        sampleRate = if (realtimePacing) sampleRate else 0,
                        isCancelled = isCancelled,
                        onProgress = { sent ->
                            val kb = sent / 1024
                            if (kb - lastLoggedKB >= 256) {
                                lastLoggedKB = kb
                                onStep(Step.Info("   ...已推 ${kb}KB"))
                            }
                        }
                    )
                },
                isCancelled = isCancelled
            )
            // 末包补零发出（对齐 pyatv：只末包补零，杜绝块边界残包）
            val tailPkts = sender?.flushFinal(socket, target, result.dataPort) ?: 0
            if (tailPkts > 0) onStep(Step.Info("已补发末包（补零到满 352 帧）"))
            // 末尾静音 padding（对齐 pyatv：补到 padding≥latency），整段干净播完不被提前 cut
            val padPkts = sender?.sendSilencePadding(
                socket = socket, target = target, port = result.dataPort,
                paddingFrames = clock.latency.toInt(),
                sampleRate = if (realtimePacing) sampleRate else 0,
                isCancelled = isCancelled
            ) ?: 0
            onStep(Step.Info("已补发 $padPkts 个静音 padding 包（padding≈latency=${clock.latency}帧）"))
            onStep(Step.Success("音频推流完成 ✓（已启用 NTP timing + Sync 锚点 + feedback）"))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "streamAudio error", t)
            onStep(Step.Failure("推流异常: ${t.message}", t))
            false
        } finally {
            runCatching { socket.close() }
            stopFeedback()
            runCatching { syncSender?.stop() }; syncSender = null
            runCatching { timingServer?.stop() }; timingServer = null
            runCatching { eventChannel?.stop() }; eventChannel = null
            runCatching { controlSocket?.close() }; controlSocket = null
        }
    }

    /**
     * 实时系统音频捕获推流到真设备（Step 2：真实音乐链路）。
     *
     * 与 [streamAudio] 共享同一套已在 HomePod 验证出声的编排
     * （clock / Sync / RECORD / FLUSH / volume / EncryptedRtpAudioSender），
     * 唯一区别是音源换成【实时 PCM 流】：
     *  - 输入 PCM16-LE（44100/16/2，与 [com.airsonic.sender.capture.SystemAudioCapture] 一致），
     *    由 makePayload 内部 byteswap 成 L16 大端上线（与文件路径完全相同）。
     *  - 不做 realtime 节流（sampleRate=0）：捕获本身即实时速率，[nextChunk] 阻塞读取天然定速；
     *    时钟由每包发送推进，与 Sync 锚点自然对齐。
     *  - 运行到 [isCancelled] 为 true 或 [nextChunk] 返回 null 为止，然后补末包收尾。
     *
     * @param channels 声道数（捕获默认立体声 2）
     * @param nextChunk 阻塞读取下一块 PCM16-LE；返回 null 表示音源结束
     */
    fun streamCapturedPcm(
        result: StreamResult,
        channels: Int = 2,
        isCancelled: () -> Boolean = { false },
        nextChunk: () -> ByteArray?,
        onStep: (Step) -> Unit
    ): Boolean {
        val socket = DatagramSocket()
        return try {
            activeOnStep = onStep
            val target = InetAddress.getByName(host)

            val clock = AirplayClock(sampleRate = 44100, channels = channels)
            val seq0 = CryptoPrimitives.randomBytes(2)
                .fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) } and 0xFFFF

            // Sync 发送器（复用 SETUP#2 声明的控制 socket，源端口一致 —— HomePod 出声前提）
            if (result.controlPort > 0) {
                val ss = AirplaySyncSender(clock, host, result.controlPort, externalSocket = controlSocket)
                runCatching { ss.start() }
                    .onSuccess { syncSender = ss; onStep(Step.Info("Sync 发送器已启动 → controlPort=${result.controlPort}")) }
                    .onFailure { onStep(Step.Info("Sync 启动失败: ${it.message}（继续）")) }
            } else {
                onStep(Step.Info("无设备 controlPort，跳过 Sync（可能静音）"))
            }

            startFeedback(onStep)

            // 对齐 pyatv：reset(一次) → RECORD → FLUSH → volume → 音频包
            clock.reset()
            val rtptime0 = clock.rtptime32
            val sender = EncryptedRtpAudioSender(
                audioKey = result.audioKey,
                ssrc = sessionId.toInt(),
                alac = if (useAlac) AlacEncoder(channels = channels) else null,  // Sonos 等→ALAC；否则 L16 PCM
                channels = channels,
                initialSeq = seq0,
                initialTimestamp = rtptime0,
                clock = clock
            )
            record(seq = seq0, rtptime = rtptime0, onStep = onStep)
            flush(seq = seq0, rtptime = rtptime0, onStep = onStep)
            setVolume(volume = -15.0, onStep = onStep)

            onStep(Step.Info("开始实时捕获推流 → dataPort=${result.dataPort}（无节流，捕获即实时），首包 ts=$rtptime0"))
            var totalKB = 0L
            var lastLoggedKB = 0L
            while (!isCancelled()) {
                val chunk = nextChunk() ?: break
                if (chunk.isEmpty()) continue
                sender.sendOverUdp(
                    socket = socket,
                    target = target,
                    port = result.dataPort,
                    pcm = chunk,
                    framesPerPacket = 352,
                    sampleRate = 0,            // 不节流：捕获本身即实时速率
                    isCancelled = isCancelled,
                    onProgress = {}
                )
                totalKB += chunk.size / 1024
                if (totalKB - lastLoggedKB >= 512) {
                    lastLoggedKB = totalKB
                    onStep(Step.Info("   ...已推 ${totalKB}KB"))
                }
            }
            sender.flushFinal(socket, target, result.dataPort)
            onStep(Step.Success("实时捕获推流结束 ✓（共 ${totalKB}KB）"))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "streamCapturedPcm error", t)
            onStep(Step.Failure("实时推流异常: ${t.message}", t))
            false
        } finally {
            runCatching { socket.close() }
            stopFeedback()
            runCatching { syncSender?.stop() }; syncSender = null
            runCatching { timingServer?.stop() }; timingServer = null
            runCatching { eventChannel?.stop() }; eventChannel = null
            runCatching { controlSocket?.close() }; controlSocket = null
        }
    }

    /** 启动 feedback 心跳（每 2s POST /feedback，对齐 pyatv FEEDBACK_INTERVAL=2.0s）。 */
    private fun startFeedback(onStep: (Step) -> Unit) {
        val ch = channel ?: return
        feedbackRunning = true
        feedbackThread = Thread({
            while (feedbackRunning) {
                try {
                    Thread.sleep(2000L)
                    if (!feedbackRunning) break
                    runCatching {
                        rtspEncrypted(ch, "POST", null, extraHeaders = mapOf(), uriOverride = "/feedback")
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                    // best-effort，忽略错误（对齐 pyatv）
                }
            }
        }, "airplay-feedback").apply { isDaemon = true; start() }
        onStep(Step.Info("feedback 心跳已启动（2s 周期）"))
    }

    private fun stopFeedback() {
        feedbackRunning = false
        feedbackThread?.interrupt()
        feedbackThread = null
    }

    private data class RtspResp(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun rtspEncrypted(
        ch: HapEncryptedChannel,
        method: String,
        body: ByteArray?,
        contentType: String = "application/x-apple-binary-plist",
        extraHeaders: Map<String, String> = emptyMap(),
        uriOverride: String? = null
    ): RtspResp {
        val url = uriOverride ?: "rtsp://$localIp/$sessionId"
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: ${cseq++}\r\n")
        sb.append("User-Agent: AirPlay/950.7.1\r\n")
        sb.append("DACP-ID: 0000000000000001\r\n")
        sb.append("Active-Remote: 1\r\n")
        sb.append("Client-Instance: 0000000000000001\r\n")
        for ((k, v) in extraHeaders) sb.append("$k: $v\r\n")
        if (body != null) {
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Content-Length: ${body.size}\r\n")
        }
        sb.append("\r\n")
        val req = sb.toString().toByteArray(Charsets.US_ASCII) + (body ?: ByteArray(0))
        ch.sendEncrypted(req)
        val raw = ch.recvResponse()
        return parseRtsp(raw)
    }

    private fun parseRtsp(raw: ByteArray): RtspResp {
        var sep = -1
        for (i in 0..raw.size - 4) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
            ) { sep = i; break }
        }
        if (sep < 0) return RtspResp(-1, emptyMap(), ByteArray(0))
        val headerText = String(raw, 0, sep, Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val status = Regex("(?:RTSP|HTTP)/1\\.[01]\\s+(\\d+)").find(lines.firstOrNull() ?: "")
            ?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] =
                lines[i].substring(idx + 1).trim()
        }
        val body = raw.copyOfRange(sep + 4, raw.size)
        Log.d(TAG, "RTSP(enc) <= $status, bodyLen=${body.size}")
        return RtspResp(status, headers, body)
    }

    companion object {
        private const val TAG = "AirplayStreamSession"
    }
}