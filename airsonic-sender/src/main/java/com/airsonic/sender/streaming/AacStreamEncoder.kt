// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * 实时 AAC-LC 编码器：start() 后反复 encode(pcm) 喂 PCM16-LE（44100/2ch），
 * 每产出一帧裸 AAC 即加 ADTS 头通过 onFrame 回调。stop() 释放。
 * 注意：单线程使用（捕获线程内同步喂入/取出）。
 */
class AacStreamEncoder(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val bitRate: Int = 128_000,
    private val onFrame: (ByteArray) -> Unit
) {
    private var codec: MediaCodec? = null
    private val bufInfo = MediaCodec.BufferInfo()

    fun start() {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
    }

    /** 喂一块 PCM16-LE，并把已就绪的 AAC 帧取出回调。 */
    fun encode(pcm: ByteArray) {
        val c = codec ?: return
        var offset = 0
        while (offset < pcm.size) {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx < 0) break
            val inBuf: ByteBuffer = c.getInputBuffer(inIdx) ?: continue
            inBuf.clear()
            val n = minOf(inBuf.remaining(), pcm.size - offset)
            inBuf.put(pcm, offset, n)
            c.queueInputBuffer(inIdx, 0, n, 0, 0)
            offset += n
        }
        drain(c)
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufInfo, 0)
            if (outIdx < 0) break
            val outBuf = c.getOutputBuffer(outIdx)
            if (outBuf != null && bufInfo.size > 0 &&
                (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val aac = ByteArray(bufInfo.size)
                outBuf.position(bufInfo.offset)
                outBuf.get(aac)
                val adts = adtsHeader(sampleRate, channels, aac.size)
                onFrame(adts + aac)
            }
            c.releaseOutputBuffer(outIdx, false)
        }
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
