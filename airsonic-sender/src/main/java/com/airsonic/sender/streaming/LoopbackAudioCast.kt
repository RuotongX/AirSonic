// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import java.io.FileDescriptor
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 本地闭环发送器：跳过配对 / SETUP，用**固定音频密钥**把
 * 解码 → ALAC → ChaCha20-Poly1305 加密 → UDP 的管道直接推到电脑接收端。
 *
 * 目的：在没有真设备时，验证 [AirplayStreamSession.streamAudio] 所用的
 * 「解码 / ALAC 编码 / 加密 / 分包」逻辑本身字节级正确（接收端 recon/loopback_recv.py
 * 用相同 key/nonce/ALAC 布局解密落盘 WAV）。
 *
 * 固定密钥 = bytes(0..31)，与 recon/loopback_recv.py 中 KEY 一致（零歧义）。
 */
class LoopbackAudioCast(
    private val host: String,
    private val port: Int = 6010
) {
    sealed class Event {
        data class Info(val message: String) : Event()
        data class Success(val message: String) : Event()
        data class Failure(val message: String, val cause: Throwable? = null) : Event()
    }

    fun cast(
        fd: FileDescriptor,
        realtimePacing: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onEvent: (Event) -> Unit
    ): Boolean {
        val socket = DatagramSocket()
        return try {
            val target = InetAddress.getByName(host)
            val framesPerPacket = 352
            val key = ByteArray(32) { it.toByte() }   // bytes(0..31)
            onEvent(Event.Info("本地闭环 -> $host:$port（固定 key，跳过配对/SETUP）"))

            val decoder = LocalAudioDecoder()
            var sender: EncryptedRtpAudioSender? = null
            var sampleRate = 44100
            var lastKB = 0L

            decoder.decode(
                fd = fd,
                onFormat = { fmt ->
                    sampleRate = fmt.sampleRate
                    val alac = AlacEncoder(
                        sampleRate = fmt.sampleRate,
                        channels = fmt.channels,
                        sampleSize = 16,
                        framesPerPacket = framesPerPacket
                    )
                    sender = EncryptedRtpAudioSender(
                        audioKey = key,
                        alac = alac,
                        channels = fmt.channels
                    )
                    onEvent(Event.Info("格式 sr=${fmt.sampleRate} ch=${fmt.channels}，ALAC 加密推流就绪"))
                },
                onPcm = { chunk ->
                    sender?.sendOverUdp(
                        socket = socket,
                        target = target,
                        port = port,
                        pcm = chunk,
                        framesPerPacket = framesPerPacket,
                        sampleRate = if (realtimePacing) sampleRate else 0,
                        isCancelled = isCancelled,
                        onProgress = { sent ->
                            val kb = sent / 1024
                            if (kb - lastKB >= 256) {
                                lastKB = kb
                                onEvent(Event.Info("   ...已推 ${kb}KB"))
                            }
                        }
                    )
                },
                isCancelled = isCancelled
            )
            onEvent(Event.Success("本地闭环推流完成 ✓（在电脑端 Ctrl-C 写出 WAV 检验）"))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "loopback cast error", t)
            onEvent(Event.Failure("本地闭环异常: ${t.message}", t))
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "LoopbackAudioCast"
    }
}