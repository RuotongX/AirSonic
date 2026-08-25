// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Test
import java.io.File

/**
 * 离线产「与 harness/手机端完全同款」的 HLS 分片文件到 /tmp/airsonic-http/ourseg_<n>.ts，
 * 供 ffprobe/ffmpeg 离线解剖（对照 ffmpeg 产的 hls/live*.ts）。
 */
class HlsSegmentDumpTest {

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

    @Test fun `离线产 HLS 分片供解剖`() {
        val h264File = File("/tmp/airsonic-http/sample.h264")
        val aacFile = File("/tmp/airsonic-http/sample.aac")
        if (!h264File.exists()) { println("skip: no sample.h264"); return }
        data class AU(val data: ByteArray, val key: Boolean)
        val aus = ArrayList<AU>()
        var sps: ByteArray? = null; var pps: ByteArray? = null
        for (nal in splitNals(h264File.readBytes())) {
            var h = 0; while (h + 1 < nal.size && nal[h] == 0.toByte()) h++
            h++
            if (h >= nal.size) continue
            when (nal[h].toInt() and 0x1F) {
                7 -> if (sps == null) sps = nal
                8 -> if (pps == null) pps = nal
                5 -> aus.add(AU(nal, true))
                1 -> aus.add(AU(nal, false))
            }
        }
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

        // 直接复刻 HlsLiveServer 的分片语义（不开 HTTP）：每个关键帧 forcePatPmt + 切片
        val segs = ArrayList<ByteArray>()
        val cur = java.io.ByteArrayOutputStream()
        val muxer = TsMuxer(audioPid = if (aacFrames.isNotEmpty()) 0x102 else null) { pkt -> cur.write(pkt) }
        muxer.setSpsPps(sps!!, pps!!)
        val frameDurUs = 33_333L; val aacDurUs = 23_220L
        val ptsOffset = 1_000_000L
        var ai = 0
        aus.forEachIndexed { i, au ->
            val pts = ptsOffset + i * frameDurUs
            if (au.key) {
                muxer.forcePatPmt()
                if (cur.size() > 0) { segs.add(cur.toByteArray()); cur.reset() }
            }
            muxer.writeVideoFrame(au.data, pts, au.key)
            while (ai < aacFrames.size && ai * aacDurUs <= i * frameDurUs) {
                muxer.writeAudioFrame(aacFrames[ai], ptsOffset + ai * aacDurUs); ai++
            }
        }
        if (cur.size() > 0) segs.add(cur.toByteArray())

        segs.take(4).forEachIndexed { n, data ->
            File("/tmp/airsonic-http/ourseg_$n.ts").writeBytes(data)
            println(">>> ourseg_$n.ts ${data.size}B (${data.size / 188} pkts)")
        }
    }
}
