// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

/**
 * ADTS（Audio Data Transport Stream）帧头构造，AAC-LC、无 CRC（7 字节）。
 * 每个 MediaCodec 输出的裸 AAC 帧前都要加一个，Sonos/通用解码器才能流式解析。
 */

/** MPEG-4 采样率索引表。 */
private val SAMPLE_RATE_INDEX = intArrayOf(
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
    16000, 12000, 11025, 8000, 7350
)

private fun freqIndex(sampleRate: Int): Int =
    SAMPLE_RATE_INDEX.indexOf(sampleRate).let { if (it < 0) 4 else it } // 默认 44100

/**
 * @param aacFrameLen MediaCodec 输出的裸 AAC 负载字节数（不含本头）
 * @return 7 字节 ADTS 头
 */
fun adtsHeader(sampleRate: Int, channels: Int, aacFrameLen: Int): ByteArray {
    val profile = 1               // AAC-LC（AOT 2 - 1）
    val freqIdx = freqIndex(sampleRate)
    val chan = channels and 0x07
    val frameLen = aacFrameLen + 7

    val h = ByteArray(7)
    h[0] = 0xFF.toByte()
    h[1] = 0xF1.toByte()          // sync(1111) + MPEG4(0) + layer(00) + protection_absent(1)
    h[2] = (((profile and 0x03) shl 6) or
            ((freqIdx and 0x0F) shl 2) or
            ((chan and 0x04) shr 2)).toByte()
    h[3] = (((chan and 0x03) shl 6) or ((frameLen shr 11) and 0x03)).toByte()
    h[4] = ((frameLen shr 3) and 0xFF).toByte()
    h[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()  // buffer fullness 高 5 位=1
    h[6] = 0xFC.toByte()          // buffer fullness 低 6 位=1 + num_frames-1=0
    return h
}
