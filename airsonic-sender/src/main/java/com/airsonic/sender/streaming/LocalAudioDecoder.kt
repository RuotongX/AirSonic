// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * 本地音频文件解码器：把 mp3/m4a/aac/flac/wav 等解码为 PCM16-LE。
 *
 * 用 MediaExtractor 解封装 + MediaCodec 解码，按块回调 PCM，
 * 供 [RtpAudioSender] 边解码边推流（低内存）。
 *
 * 这是"本地文件直投"路径的音源端 —— 不依赖系统音频捕获，
 * 因此不受国产 ROM 的 AudioPlaybackCapture 限制。
 */
class LocalAudioDecoder {

    data class AudioInfo(val sampleRate: Int, val channels: Int)

    /**
     * 解码音频文件，按块回调 PCM。
     * @return 解出的音频参数（采样率/声道）
     */
    fun decode(
        fd: FileDescriptor,
        onFormat: (AudioInfo) -> Unit,
        onPcm: (ByteArray) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): AudioInfo {
        val extractor = MediaExtractor()
        extractor.setDataSource(fd)
        val trackIndex = selectAudioTrack(extractor)
        require(trackIndex >= 0) { "未找到音频轨道" }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("无 MIME")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = AudioInfo(sampleRate, channels)
        onFormat(info)
        Log.d(TAG, "decode mime=$mime sr=$sampleRate ch=$channels")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS && !isCancelled()) {
            if (!sawInputEOS) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outBuf.position(bufferInfo.offset)
                    outBuf.get(chunk, 0, bufferInfo.size)
                    onPcm(chunk)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEOS = true
                }
            }
        }

        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        return info
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    companion object {
        private const val TAG = "LocalAudioDecoder"
    }
}