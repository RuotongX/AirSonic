// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.screen

import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import com.airsonic.sender.streaming.TsMuxer
import kotlin.concurrent.thread

/**
 * 屏幕镜像采集编码器：MediaProjection → VirtualDisplay → MediaCodec(H.264 surface 输入)
 * → Annex-B → TsMuxer → 188B TS 包经 [onTsPacket] 扇出（交给 HTTP 流服务器）。
 *
 * 编码参数按 DLNA 实时流调优：Baseline（无 B 帧，PTS=DTS）、1s GOP、码率可调。
 * 新观众中途接入最坏花屏到下一关键帧（≤1s 自愈）。
 */
class ScreenMirrorCaster(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val dpi: Int = 320,
    private val bitRate: Int = 6_000_000,
    private val frameRate: Int = 30,
    private val iFrameIntervalSec: Int = 1,
    private val onTsPacket: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit = { Log.i("ScreenMirror", it) },
) {
    private var codec: MediaCodec? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {}
    private val muxer = TsMuxer(onPacket = onTsPacket)
    @Volatile private var running = false
    private var drainThread: Thread? = null
    /** 编码器是否已产出 SPS/PPS（未产出前 DLNA Play 无意义）。 */
    @Volatile var ready = false; private set
    /** start 失败原因（vivo 等 ROM 屏蔽 logcat，诊断须透传到 UI 状态行）。 */
    @Volatile var lastError: String? = null; private set

    /** 开始采集编码。[projection] 须已授权且前台 Service 已起。返回 false=编码器初始化失败（原因见 [lastError]）。 */
    fun start(projection: MediaProjection): Boolean {
        this.projection = projection
        // Android 14+ 强制：createVirtualDisplay 前必须 registerCallback，否则 SecurityException
        runCatching { projection.registerCallback(projectionCallback, null) }
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            // Baseline：无 B 帧 → PTS=DTS，PES 打包不用处理重排；兼容性也最好
            runCatching {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
        }
        val c = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                try {
                    configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (t: Throwable) {
                    // 兜底：个别 ROM 编码器拒 Baseline profile，去掉 profile 重配一次
                    onLog("带 Baseline 配置被拒(${t.message})，去 profile 重试")
                    fmt.removeKey(MediaFormat.KEY_PROFILE)
                    configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }
        } catch (t: Throwable) {
            lastError = "编码器初始化: ${t.javaClass.simpleName} ${t.message}"
            onLog("编码器初始化失败: ${t.message}")
            return false
        }
        val surface = try {
            val s = c.createInputSurface()
            c.start()
            s
        } catch (t: Throwable) {
            lastError = "编码器start: ${t.javaClass.simpleName} ${t.message}"
            onLog("编码器 start 失败: ${t.message}")
            runCatching { c.release() }
            return false
        }
        codec = c
        try {
            display = projection.createVirtualDisplay(
                "airsonic-mirror", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
        } catch (t: Throwable) {
            lastError = "虚拟屏: ${t.javaClass.simpleName} ${t.message}"
            onLog("createVirtualDisplay 失败: ${t.message}")
            runCatching { c.stop() }; runCatching { c.release() }; codec = null
            return false
        }
        running = true
        drainThread = thread(isDaemon = true, name = "airsonic-screen-drain") { drainLoop(c) }
        onLog("录屏编码已启动 ${width}x${height}@${frameRate} bitrate=$bitRate")
        return true
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (t: Throwable) {
                if (running) onLog("dequeue 异常: ${t.message}")
                break
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    onLog("编码器格式: ${c.outputFormat}")
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        buf.get(data)
                        handleFrame(data, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    private fun handleFrame(data: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // csd-0：Annex-B 的 SPS+PPS 拼接，拆出来缓存
            val nals = splitAnnexB(data)
            if (nals.size >= 2) {
                muxer.setSpsPps(withStartCode(nals[0]), withStartCode(nals[1]))
                ready = true
                onLog("SPS/PPS 已缓存 (${nals[0].size}B/${nals[1].size}B)")
            }
            return
        }
        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        muxer.writeVideoFrame(data, info.presentationTimeUs, keyframe)
    }

    /** 让编码器立刻产一个关键帧（新观众接入时用，减轻中途花屏窗口）。 */
    fun requestSyncFrame() {
        runCatching {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    fun stop() {
        running = false
        drainThread?.join(1500)
        runCatching { display?.release() }; display = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }; codec = null
        projection?.let { runCatching { it.unregisterCallback(projectionCallback) } }
        projection = null
        onLog("录屏编码已停止")
    }

    /** 把 Annex-B 缓冲拆成裸 NAL（去起始码）。 */
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        while (i + 3 <= data.size) {
            val is3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val is4 = i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (is3 || is4) {
                if (start >= 0 && start < i) out += data.copyOfRange(start, i)
                i += if (is4) 4 else 3
                start = i
            } else i++
        }
        if (start >= 0 && start < data.size) out += data.copyOfRange(start, data.size)
        return out
    }

    private fun withStartCode(nal: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1) + nal
}
