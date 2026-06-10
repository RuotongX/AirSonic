// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

/**
 * 无限长 WAV 直播流的 44 字节 RIFF 头：RIFF/data 长度填 0xFFFFFFFF（约 4GB，
 * swyh-rs / BubbleUPnP 同款做法），后接裸 PCM16-LE 持续写出即可被 Sonos 等渲染器播放。
 */
fun wavStreamHeader(sampleRate: Int, channels: Int, bitsPerSample: Int = 16): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val h = ByteArray(44)
    fun put4(off: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(h, off) }
    fun putLe32(off: Int, v: Long) {
        h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v shr 8) and 0xFF).toByte()
        h[off + 2] = ((v shr 16) and 0xFF).toByte(); h[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
    fun putLe16(off: Int, v: Int) { h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v shr 8) and 0xFF).toByte() }
    put4(0, "RIFF"); putLe32(4, 0xFFFFFFFFL); put4(8, "WAVE")
    put4(12, "fmt "); putLe32(16, 16); putLe16(20, 1 /*PCM*/); putLe16(22, channels)
    putLe32(24, sampleRate.toLong()); putLe32(28, byteRate.toLong()); putLe16(32, blockAlign); putLe16(34, bitsPerSample)
    put4(36, "data"); putLe32(40, 0xFFFFFFFFL)
    return h
}
