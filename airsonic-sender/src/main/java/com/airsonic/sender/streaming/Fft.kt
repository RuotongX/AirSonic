// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 频谱可视化的纯逻辑：实数 PCM → 频段幅度。零依赖、JVM 可测。
 * 用迭代 radix-2 Cooley-Tukey FFT；频段按对数频率分组（贴合人耳），动态范围用 log 压缩 + 归一化。
 */

/** 原地复数 FFT（radix-2，[re]/[im] 长度须为 2 的幂）。 */
fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    // 位反转置换
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j or bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2.0 * Math.PI / len
        val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var cwr = 1f; var cwi = 0f
            for (k in 0 until len / 2) {
                val a = i + k; val b = a + len / 2
                val tr = cwr * re[b] - cwi * im[b]
                val ti = cwr * im[b] + cwi * re[b]
                re[b] = re[a] - tr; im[b] = im[a] - ti
                re[a] += tr; im[a] += ti
                val ncwr = cwr * wr - cwi * wi
                cwi = cwr * wi + cwi * wr; cwr = ncwr
            }
            i += len
        }
        len = len shl 1
    }
}

/**
 * 从 PCM16-LE 立体声块取 [bands] 个对数频段幅度（各 0..1，驱动频谱条）。
 * 取左声道、加 Hann 窗、FFT、log 频率分组。不足一个 [fftSize] 帧返回全 0（静音）。
 */
fun spectrumBands(pcm: ByteArray, bands: Int = 24, fftSize: Int = 1024): FloatArray {
    val out = FloatArray(bands)
    val frames = pcm.size / 4   // 立体声 16bit：每帧 4 字节
    if (frames < fftSize) return out
    val re = FloatArray(fftSize)
    val im = FloatArray(fftSize)
    for (i in 0 until fftSize) {
        val lo = pcm[i * 4].toInt() and 0xFF
        val hi = pcm[i * 4 + 1].toInt() shl 8
        val s = (hi or lo).toShort().toInt()
        val w = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))   // Hann 窗
        re[i] = (s / 32768f) * w.toFloat()
    }
    fft(re, im)
    val half = fftSize / 2
    for (b in 0 until bands) {
        val loBin = half.toDouble().pow(b.toDouble() / bands).toInt().coerceIn(1, half - 1)
        val hiBin = half.toDouble().pow((b + 1.0) / bands).toInt().coerceIn(loBin + 1, half)
        var sum = 0f
        for (k in loBin until hiBin) sum += sqrt(re[k] * re[k] + im[k] * im[k])
        val avg = sum / (hiBin - loBin)
        out[b] = ln(1f + avg * 16f).coerceIn(0f, 1f)   // log 压缩动态范围 + 归一化
    }
    return out
}
