// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.abs

/**
 * Phase 0 验证点 2：[AudioPlaybackCapture] 系统音频捕获。
 *
 * 通过 [MediaProjection] 授权后，捕获 USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN
 * 的播放音频为 PCM。配套 [SilenceDetector] 用于判断捕获是否被目标 App 拒绝
 * （表现为长时间静音），从而驱动「降级到本地文件直投」。
 *
 * 用途：在真机/华为机型上对 QQ音乐/网易云/汽水等逐个实测，建立支持兼容性表。
 */
class SystemAudioCapture(
    private val sampleRate: Int = 44100,
    private val channelMask: Int = AudioFormat.CHANNEL_IN_STEREO,
    private val encoding: Int = AudioFormat.ENCODING_PCM_16BIT
) {

    /** 捕获结果统计，供兼容性表使用。 */
    data class CaptureStats(
        val totalFrames: Long,
        val totalBytes: Long,
        val nonSilentFrames: Long,
        val peakAmplitude: Int
    ) {
        val isLikelyCapturing: Boolean
            get() = nonSilentFrames > 0 && peakAmplitude > SILENCE_THRESHOLD
    }

    private var audioRecord: AudioRecord? = null

    @Volatile
    var running: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(projection: MediaProjection): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufferSize = if (minBuf > 0) minBuf * 2 else sampleRate * 2

        return try {
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                record.release()
                return false
            }
            audioRecord = record
            record.startRecording()
            running = true
            Log.i(TAG, "Capture started, bufferSize=$bufferSize")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start capture failed", t)
            false
        }
    }

    /**
     * 以 Flow 形式持续输出 PCM 块；同时累积统计供兼容性判定。
     */
    fun pcmFlow(chunkBytes: Int = 4096): Flow<ByteArray> = flow {
        val record = audioRecord ?: error("call start() first")
        val buffer = ByteArray(chunkBytes)
        while (running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read > 0) {
                emit(buffer.copyOf(read))
            } else if (read < 0) {
                Log.w(TAG, "AudioRecord.read error=$read")
                break
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 阻塞读取一块 PCM16-LE（供实时推流 `streamCapturedPcm` 的 nextChunk 使用）。
     * AudioRecord.read 天然阻塞到有数据，提供实时定速。
     * @return PCM 块；读到 0 字节返回空数组（继续）；出错/停止返回 null（结束）。
     */
    fun readChunk(chunkBytes: Int = 4096): ByteArray? {
        val record = audioRecord ?: return null
        if (!running) return null
        val buffer = ByteArray(chunkBytes)
        val read = record.read(buffer, 0, buffer.size)
        return when {
            read > 0 -> buffer.copyOf(read)
            read == 0 -> ByteArray(0)
            else -> null
        }
    }

    /**
     * 便捷阻塞式采样：采集 [durationMs] 毫秒并返回统计，用于兼容性快速实测。
     */
    fun sampleForStats(durationMs: Long): CaptureStats {
        val record = audioRecord ?: error("call start() first")
        val buffer = ByteArray(4096)
        var totalFrames = 0L
        var totalBytes = 0L
        var nonSilent = 0L
        var peak = 0

        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline && running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            totalBytes += read
            totalFrames += read / 2
            var i = 0
            var localNonSilent = false
            while (i + 1 < read) {
                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                val amp = abs(sample)
                if (amp > peak) peak = amp
                if (amp > SILENCE_THRESHOLD) localNonSilent = true
                i += 2
            }
            if (localNonSilent) nonSilent += read / 2
        }
        return CaptureStats(totalFrames, totalBytes, nonSilent, peak)
    }

    fun stop() {
        running = false
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
        Log.i(TAG, "Capture stopped")
    }

    companion object {
        private const val TAG = "SystemAudioCapture"
        /** 16-bit PCM 静音判定阈值，约 -54 dBFS 以下视为静音。 */
        const val SILENCE_THRESHOLD = 64
    }
}