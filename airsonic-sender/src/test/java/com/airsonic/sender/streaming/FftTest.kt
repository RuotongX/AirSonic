// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class FftTest {
    @Test fun fft_singleBinForPureTone() {
        val n = 64
        val k0 = 5   // 第 5 个频率 bin
        val re = FloatArray(n) { sin(2.0 * PI * k0 * it / n).toFloat() }
        val im = FloatArray(n)
        fft(re, im)
        // 纯正弦的能量应集中在 bin k0（与 n-k0 对称）
        val mag = FloatArray(n) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val peak = mag.withIndex().maxByOrNull { it.value }!!.index
        assertTrue("峰值应在 $k0 或 ${n - k0}, 实际 $peak", peak == k0 || peak == n - k0)
    }

    /** 立体声 PCM16-LE：用频率 freq 的正弦填左右声道。 */
    private fun tonePcm(freq: Double, frames: Int, sampleRate: Int = 44100): ByteArray {
        val b = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (sin(2.0 * PI * freq * i / sampleRate) * 30000).roundToInt().toShort()
            val lo = (s.toInt() and 0xFF).toByte()
            val hi = ((s.toInt() shr 8) and 0xFF).toByte()
            b[i * 4] = lo; b[i * 4 + 1] = hi        // 左
            b[i * 4 + 2] = lo; b[i * 4 + 3] = hi    // 右
        }
        return b
    }

    @Test fun spectrum_silenceIsAllZero() {
        val out = spectrumBands(ByteArray(1024 * 4))
        assertTrue(out.all { it == 0f })
    }

    @Test fun spectrum_shortInputReturnsZero() {
        assertTrue(spectrumBands(ByteArray(100)).all { it == 0f })
    }

    @Test fun spectrum_lowToneLightsLowBands() {
        val out = spectrumBands(tonePcm(120.0, 1024), bands = 24)   // 120Hz 低频
        val lowEnergy = out.take(8).sum()
        val highEnergy = out.takeLast(8).sum()
        assertTrue("低频应比高频强: low=$lowEnergy high=$highEnergy", lowEnergy > highEnergy)
        assertTrue("应有非零能量", out.any { it > 0.01f })
    }

    @Test fun spectrum_bandCountMatches() {
        assertEquals(16, spectrumBands(ByteArray(1024 * 4), bands = 16).size)
    }
}
