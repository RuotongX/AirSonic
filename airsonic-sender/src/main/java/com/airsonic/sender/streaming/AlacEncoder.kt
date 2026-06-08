package com.airsonic.sender.streaming

import java.io.ByteArrayOutputStream

/**
 * ALAC（Apple Lossless）**未压缩帧**编码器。
 *
 * 真 AirPlay2 设备（AppleTV/HomePod）默认走 ALAC（feature 位 ALAC_44100_16_2 = 1<<18）。
 * 完整 ALAC 压缩（rice + LPC）实现复杂；这里用 ALAC 容器的"非压缩"分支：
 * 比特流头标记 is_compressed=0，直接写原始样本。解码端（ffmpeg/CoreAudio）
 * 完全支持该模式，码率≈PCM 但协议合规，足以让真设备出声。
 *
 * 比特布局已用 ffmpeg `alac.c` 解码器验证**无损往返（误差=0）**：
 *   3b  TYPE_CPE(1)
 *   4b  element instance tag = 0
 *   12b unused = 0
 *   1b  has_size = 1
 *   2b  extra_bits count = 0
 *   1b  is_compressed_bit = 1  (=> 非压缩分支)
 *   32b nb_samples
 *   nb_samples × [L ss-bit, R ss-bit] 交错
 *   3b  TYPE_END(7)，按字节补齐
 *
 * Magic cookie（36B QuickTime atom）经 [magicCookie] 提供，
 * 用于 RTSP SETUP 时声明给接收端（真设备 / 自研接收端解码所需 extradata）。
 */
class AlacEncoder(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val sampleSize: Int = 16,
    private val framesPerPacket: Int = 352
) {
    companion object {
        private const val TYPE_CPE = 1   // channel pair element（立体声）
        private const val TYPE_SCE = 0   // single channel element（单声道）
        private const val TYPE_END = 7
    }

    /** 36 字节 ALAC magic cookie（extradata），SETUP 阶段声明格式用。 */
    fun magicCookie(): ByteArray {
        val o = ByteArrayOutputStream(36)
        fun be32(v: Int) { o.write((v ushr 24) and 0xFF); o.write((v ushr 16) and 0xFF); o.write((v ushr 8) and 0xFF); o.write(v and 0xFF) }
        fun be16(v: Int) { o.write((v ushr 8) and 0xFF); o.write(v and 0xFF) }
        be32(36)                       // atom size
        o.write('a'.code); o.write('l'.code); o.write('a'.code); o.write('c'.code)
        be32(0)                        // version
        be32(framesPerPacket)          // samples per frame
        o.write(0)                     // compatible version
        o.write(sampleSize)            // sample size
        o.write(40)                    // history mult
        o.write(10)                    // initial history
        o.write(14)                    // rice param limit
        o.write(channels)              // channels
        be16(255)                      // maxRun
        be32(0)                        // max coded frame size
        be32(0)                        // average bitrate
        be32(sampleRate)               // sample rate
        return o.toByteArray()
    }

    /** magic cookie 的十六进制串（SETUP / SDP 用）。 */
    fun magicCookieHex(): String = magicCookie().joinToString("") { "%02x".format(it) }

    private class BitWriter {
        private var acc = 0L
        private var nbits = 0
        val out = ByteArrayOutputStream()
        fun write(value: Int, bits: Int) {
            val v = (value.toLong() and ((1L shl bits) - 1))
            acc = (acc shl bits) or v
            nbits += bits
            while (nbits >= 8) {
                nbits -= 8
                out.write(((acc ushr nbits) and 0xFF).toInt())
            }
        }
        fun finish(): ByteArray {
            if (nbits > 0) {
                out.write(((acc shl (8 - nbits)) and 0xFF).toInt())
                nbits = 0
            }
            return out.toByteArray()
        }
    }

    /**
     * 把交错 PCM16-LE 编码为一个 ALAC 帧。
     * @param pcm 交错小端 PCM，长度 = nbSamples * channels * 2
     * @param nbSamples 本帧样本数（每声道），≤ framesPerPacket
     */
    fun encodeFrame(pcm: ByteArray, nbSamples: Int): ByteArray {
        val bw = BitWriter()
        val element = if (channels == 2) TYPE_CPE else TYPE_SCE
        bw.write(element, 3)
        bw.write(0, 4)        // instance tag
        bw.write(0, 12)       // unused
        bw.write(1, 1)        // has_size
        bw.write(0, 2)        // extra_bits count = 0
        bw.write(1, 1)        // is_compressed_bit = 1 => 非压缩分支
        bw.write(nbSamples, 32)
        var p = 0
        for (i in 0 until nbSamples) {
            for (ch in 0 until channels) {
                // PCM16-LE -> signed 16
                val lo = pcm[p].toInt() and 0xFF
                val hi = pcm[p + 1].toInt()  // 保留符号
                val sample = (hi shl 8) or lo
                bw.write(sample, sampleSize)
                p += 2
            }
        }
        bw.write(TYPE_END, 3)
        return bw.finish()
    }

    /**
     * 对整段 PCM 按 framesPerPacket 切帧编码，回调每个 ALAC 帧。
     * @param onFrame (alacFrame, nbSamples) -> Unit
     */
    fun encodeStream(pcm: ByteArray, onFrame: (ByteArray, Int) -> Unit) {
        val bytesPerSample = channels * 2
        val totalSamples = pcm.size / bytesPerSample
        var sampleOff = 0
        while (sampleOff < totalSamples) {
            val n = minOf(framesPerPacket, totalSamples - sampleOff)
            val byteOff = sampleOff * bytesPerSample
            val chunk = pcm.copyOfRange(byteOff, byteOff + n * bytesPerSample)
            onFrame(encodeFrame(chunk, n), n)
            sampleOff += n
        }
    }
}
