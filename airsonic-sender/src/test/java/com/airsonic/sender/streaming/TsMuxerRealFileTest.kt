// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Test
import java.io.File

/**
 * 用真实 H.264/AAC（ffmpeg 从 sample.mp4 抽的 annexb/ADTS）过 TsMuxer 产出 ours.ts，
 * 供 harness 推给 macOS 接收端做 AVPlayer 兼容性实测（对照 ffmpeg 产的 live.ts）。
 * 输入不存在时跳过。
 */
class TsMuxerRealFileTest {

    private val h264File = File("/tmp/airsonic-http/sample.h264")
    private val aacFile = File("/tmp/airsonic-http/sample.aac")
    private val outFile = File("/tmp/airsonic-http/ours.ts")

    /** annexb → NAL 列表（每个元素含起始码）。 */
    private fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() || (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))
            ) { starts.add(i); i += 3 } else i++
        }
        val nals = ArrayList<ByteArray>()
        for (k in starts.indices) {
            val end = if (k + 1 < starts.size) starts[k + 1] else data.size
            nals.add(data.copyOfRange(starts[k], end))
        }
        return nals
    }

    @Test
    fun `真实片源过 TsMuxer 产 ours_ts`() {
        if (!h264File.exists()) { println("skip: no sample.h264"); return }
        val nals = splitNals(h264File.readBytes())
        var sps: ByteArray? = null; var pps: ByteArray? = null
        // 按「slice NAL 为一帧」聚帧（x264 baseline 单 slice/帧），并收集 SPS/PPS
        data class AU(val data: ByteArray, val key: Boolean)
        val aus = ArrayList<AU>()
        for (nal in nals) {
            // NAL 头 = 起始码(3或4B) 后的第一个字节
            var h = 0; while (h + 1 < nal.size && nal[h] == 0.toByte()) h++
            h++ // 跳过 0x01
            if (h >= nal.size) continue
            val nalType = nal[h].toInt() and 0x1F
            when (nalType) {
                7 -> if (sps == null) sps = nal
                8 -> if (pps == null) pps = nal
                5 -> aus.add(AU(nal, true))
                1 -> aus.add(AU(nal, false))
            }
        }
        println("AUs=${aus.size} keyframes=${aus.count { it.key }} sps=${sps?.size} pps=${pps?.size}")

        // ADTS 帧切分（0xFFF sync）
        val aacFrames = ArrayList<ByteArray>()
        if (aacFile.exists()) {
            val a = aacFile.readBytes()
            var p = 0
            while (p + 7 < a.size) {
                if ((a[p].toInt() and 0xFF) == 0xFF && (a[p + 1].toInt() and 0xF0) == 0xF0) {
                    val len = ((a[p + 3].toInt() and 3) shl 11) or ((a[p + 4].toInt() and 0xFF) shl 3) or ((a[p + 5].toInt() ushr 5) and 7)
                    if (len <= 0 || p + len > a.size) break
                    aacFrames.add(a.copyOfRange(p, p + len)); p += len
                } else p++
            }
        }
        println("aacFrames=${aacFrames.size}")

        val out = java.io.ByteArrayOutputStream()
        val m = TsMuxer(audioPid = if (aacFrames.isNotEmpty()) 0x102 else null) { pkt -> out.write(pkt) }
        m.setSpsPps(sps!!, pps!!)
        val frameDurUs = 33_333L
        val aacDurUs = 23_220L
        val ptsOffset = 700_000L   // 排错变量：ffmpeg 的 TS 时间戳不从 0 起（首 PCR 0.7s）
        var ai = 0
        aus.forEachIndexed { i, au ->
            m.writeVideoFrame(au.data, ptsUs = ptsOffset + i * frameDurUs, keyframe = au.key)
            // 音频 pts 追着视频走（比视频略提前没关系的直播惯例：按各自时钟递增）
            while (ai < aacFrames.size && ai * aacDurUs <= i * frameDurUs) {
                m.writeAudioFrame(aacFrames[ai], ptsUs = ptsOffset + ai * aacDurUs); ai++
            }
        }
        outFile.writeBytes(out.toByteArray())
        println(">>> wrote ${outFile} ${out.size()}B (${out.size() / 188} packets)")
    }
}
