// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

/**
 * PCM16-LE 块的归一化峰值 0..1（驱动 UI 律动）。最多采样 [maxSamples] 个样本即够。
 * 纯逻辑，JVM 可测。
 */
fun pcmPeak(pcm: ByteArray, maxSamples: Int = 2048): Float {
    var peak = 0
    var i = 0
    val n = minOf(pcm.size - 1, maxSamples)
    while (i < n) {
        val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
        val a = if (s < 0) -s else s
        if (a > peak) peak = a
        i += 2
    }
    return (peak / 32767f).coerceIn(0f, 1f)
}
