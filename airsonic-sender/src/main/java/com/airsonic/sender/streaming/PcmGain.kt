// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

/**
 * 就地给 PCM16-LE 缓冲乘增益（音量衰减）。gain ∈ [0,1]（>1 会钳顶，不建议）。
 * gain==1 时直接返回（热路径短路）。供「通用 DLNA 无 RenderingControl」降级路径用。
 */
fun scalePcm16(buf: ByteArray, len: Int, gain: Float) {
    if (gain == 1f) return
    var i = 0
    while (i + 1 < len) {
        val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
        val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
        buf[i] = (scaled and 0xFF).toByte()
        buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
        i += 2
    }
}
