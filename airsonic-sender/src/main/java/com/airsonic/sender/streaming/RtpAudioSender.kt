package com.airsonic.sender.streaming

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RTP 音频打包器（RTP-over-TCP 交错传输）。
 *
 * 帧格式（与 mini_airplay_receiver.py 对齐）：
 *   [2字节大端长度][RTP包]
 * RTP 包：
 *   12字节固定头 (V=2,PT=96, seq, timestamp, ssrc) + PCM16-LE 负载
 *
 * 结束哨兵：长度字段写 0，通知接收端落盘。
 */
class RtpAudioSender(
    private val out: OutputStream,
    private val payloadType: Int = 96,
    private val ssrc: Int = 0x11223344
) {
    private var seq = 0
    private var timestamp = 0

    /**
     * 发送一段 PCM16-LE 音频。
     * @param pcm 交错的 L/R 16bit 小端样本字节
     * @param framesPerPacket 每个 RTP 包包含的采样帧数（每帧 = 声道数 × 2 字节）
     * @param channels 声道数
     */
    fun sendPcm(pcm: ByteArray, framesPerPacket: Int = 352, channels: Int = 2) {
        val bytesPerFrame = channels * 2
        val chunkBytes = framesPerPacket * bytesPerFrame
        var offset = 0
        while (offset < pcm.size) {
            val len = minOf(chunkBytes, pcm.size - offset)
            sendPacket(pcm, offset, len, channels)
            offset += len
        }
    }

    private fun sendPacket(pcm: ByteArray, offset: Int, len: Int, channels: Int) {
        val rtp = ByteBuffer.allocate(12 + len).order(ByteOrder.BIG_ENDIAN)
        rtp.put(0x80.toByte())                 // V=2,P=0,X=0,CC=0
        rtp.put((payloadType and 0x7F).toByte()) // M=0,PT
        rtp.putShort((seq and 0xFFFF).toShort())
        rtp.putInt(timestamp)
        rtp.putInt(ssrc)
        rtp.put(pcm, offset, len)
        val rtpBytes = rtp.array()

        // 2字节长度前缀（大端）
        out.write((rtpBytes.size ushr 8) and 0xFF)
        out.write(rtpBytes.size and 0xFF)
        out.write(rtpBytes)

        seq++
        timestamp += len / (channels * 2)
    }

    /** 发送结束哨兵（长度 0），并 flush。 */
    fun finish() {
        out.write(0)
        out.write(0)
        out.flush()
    }

    fun flush() = out.flush()
}
