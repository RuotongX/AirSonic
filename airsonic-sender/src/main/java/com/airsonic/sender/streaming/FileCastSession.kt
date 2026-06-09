// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import com.airsonic.sender.pairing.PairingHandshake
import java.io.FileDescriptor

/**
 * "本地文件直投" 端到端编排：
 *   1) pair-verify（或首次 pair-setup）建立加密会话
 *   2) RTSP ANNOUNCE/SETUP/RECORD
 *   3) MediaCodec 解码本地文件为 PCM，边解边用 RTP 推流
 *
 * Phase 0 验证目标：在接收端真正"出声"（落盘 WAV）。
 *
 * 注意：此最小链路用 TCP+裸 PCM（不加密 RTP），用于打通端到端。
 * 对真 AppleTV 时，RTP 负载需替换为 ALAC 编码 + 会话密钥加密。
 */
class FileCastSession(
    private val host: String,
    private val pairPort: Int,
    private val rtspPort: Int
) {
    sealed class Event {
        data class Info(val message: String) : Event()
        data class Progress(val sentBytes: Long) : Event()
        data class Success(val message: String) : Event()
        data class Failure(val message: String, val cause: Throwable? = null) : Event()
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    /**
     * @param pin 非空则先做 pair-setup（首次配对），否则做 pair-verify
     * @param encrypted true=用会话密钥派生音频密钥并加密 RTP（贴近真设备）；
     *                  false=明文 PCM（最初的本地验证路径）
     * @param useAlac true=RTP 负载用 ALAC（未压缩）编码（贴近真设备）；false=裸 PCM(L16)。
     *                仅在 encrypted=true 时生效（真设备 ALAC 走加密 realtime）。
     */
    fun cast(fd: FileDescriptor, pin: String?, encrypted: Boolean = false, useAlac: Boolean = false, onEvent: (Event) -> Unit) {
        var rtsp: RtspClient? = null
        try {
            // 1) 配对
            onEvent(Event.Info("① 配对握手 -> $host:$pairPort"))
            val handshake = PairingHandshake(host, pairPort)
            val paired = if (!pin.isNullOrEmpty()) {
                handshake.pairSetup(pin, onStep = { relayStep(it, onEvent) }, transient = true)
            } else {
                handshake.pairVerify { relayStep(it, onEvent) }
            }
            if (!paired) {
                onEvent(Event.Failure("配对失败，终止投送"))
                return
            }

            // 加密路径需要 pair-verify 会话密钥派生音频密钥
            var audioKey: ByteArray? = null
            if (encrypted) {
                val sk = handshake.sessionKey
                if (sk == null) {
                    onEvent(Event.Failure("加密模式需 pair-verify 会话密钥，但当前为空（请用 pair-verify 而非 PIN 首配）"))
                    return
                }
                audioKey = EncryptedRtpAudioSender.deriveAudioKey(sk)
                onEvent(Event.Info("   已派生音频密钥 ${audioKey.size}B（占位算法，真设备需 SETUP 校准）"))
            }

            // 2) RTSP 握手
            onEvent(Event.Info("② RTSP 握手 -> $host:$rtspPort"))
            rtsp = RtspClient(host, rtspPort).apply { connect() }

            // 先解析音频格式（用极简预读：直接用解码器拿格式）
            val decoder = LocalAudioDecoder()
            // ALAC 编码器（仅加密+useAlac 时启用）
            var alacEncoder: AlacEncoder? = null
            val plainSender = if (!encrypted) RtpAudioSender(rtsp.outputStream) else null
            var encSender: EncryptedRtpAudioSender? = null
            var announced = false
            var channelsHolder = 2
            var totalSent = 0L
            val out = rtsp.outputStream

            // 3) 解码 + 推流
            onEvent(Event.Info("③ 解码本地文件并推流（${if (encrypted) "加密" else "明文"}${if (encrypted && useAlac) "+ALAC" else ""}）..."))
            decoder.decode(
                fd = fd,
                onFormat = { fmt ->
                    channelsHolder = fmt.channels
                    val keyHex = if (encrypted) audioKey!!.joinToString("") { "%02x".format(it) } else null
                    var alacCookieHex: String? = null
                    if (encrypted && useAlac) {
                        alacEncoder = AlacEncoder(
                            sampleRate = fmt.sampleRate,
                            channels = fmt.channels,
                            sampleSize = 16
                        )
                        alacCookieHex = alacEncoder!!.magicCookieHex()
                    }
                    encSender = if (encrypted)
                        EncryptedRtpAudioSender(audioKey!!, alac = alacEncoder, channels = fmt.channels)
                    else null
                    val r1 = rtsp!!.announce(fmt.sampleRate, fmt.channels, keyHex, alacCookieHex)
                    val r2 = rtsp!!.setup()
                    val r3 = rtsp!!.record()
                    announced = r1.status in 200..299 && r2.status in 200..299 && r3.status in 200..299
                    onEvent(Event.Info("   ANNOUNCE=${r1.status} SETUP=${r2.status} RECORD=${r3.status}  (sr=${fmt.sampleRate} ch=${fmt.channels}${if (alacCookieHex != null) " ALAC" else ""})"))
                    if (!announced) error("RTSP 握手未全部成功")
                },
                onPcm = { chunk ->
                    if (encrypted) encSender!!.sendOverTcpInterleaved(out, chunk)
                    else plainSender!!.sendPcm(chunk, channels = channelsHolder)
                    totalSent += chunk.size
                    if (totalSent % (channelsHolder * 2 * 44100) < chunk.size) {
                        onEvent(Event.Progress(totalSent))
                    }
                },
                isCancelled = { cancelled }
            )

            if (encrypted) encSender!!.finishTcp(out) else plainSender!!.finish()
            onEvent(Event.Success("投送完成 ✓ 共推送 ${totalSent / 1024}KB PCM（接收端应已出声）"))
        } catch (t: Throwable) {
            Log.e(TAG, "cast error", t)
            onEvent(Event.Failure("投送异常: ${t.message}", t))
        } finally {
            runCatching { rtsp?.teardown() }
            runCatching { rtsp?.close() }
        }
    }

    private fun relayStep(step: PairingHandshake.Step, onEvent: (Event) -> Unit) {
        when (step) {
            is PairingHandshake.Step.Info -> onEvent(Event.Info("   · ${step.message}"))
            is PairingHandshake.Step.Success -> onEvent(Event.Info("   ✓ ${step.message}"))
            is PairingHandshake.Step.Failure -> onEvent(Event.Failure(step.message, step.cause))
        }
    }

    companion object {
        private const val TAG = "FileCastSession"
    }
}