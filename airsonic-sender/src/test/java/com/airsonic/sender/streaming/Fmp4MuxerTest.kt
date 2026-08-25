// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fmp4Muxer：init 段 / fragment 的 box 结构、avcC/esds 内容、样本时长与数量、AVCC 转换。 */
class Fmp4MuxerTest {
    // 来自 /tmp/airsonic-http/sample.h264（与 ffmpeg 参考流 /tmp/llref 同源）：Baseline 1280x720
    private val SPS = hex("6742c01fd9005005bb0110000003001000000303c0f1832480")
    private val PPS = hex("68cb83cb20")

    // ---- 最小 box 解析器（只够本测试用）----
    private data class Box(val type: String, val off: Int, val size: Int)
    private fun boxes(data: ByteArray, off: Int = 0, end: Int = data.size): List<Box> {
        val out = ArrayList<Box>()
        var p = off
        while (p + 8 <= end) {
            val size = u32(data, p)
            out += Box(String(data, p + 4, 4, Charsets.ISO_8859_1), p, size)
            p += size
        }
        assertEquals("box 边界须对齐", end, p)
        return out
    }
    private fun child(data: ByteArray, parent: Box, type: String, fromEnd: Int = 0): Box {
        // avc1/mp4a 样本条目：子 box 前有 78/28 字节固定头
        val hdr = when (parent.type) {
            "meta" -> 12
            "avc1" -> 8 + 78
            "mp4a" -> 8 + 28
            else -> 8
        }
        for (b in boxes(data, parent.off + hdr, parent.off + parent.size - fromEnd)) {
            if (b.type == type) return b
            // 容器 box 递归向下找（stsd 的样本条目 avc1/mp4a 也当容器——其固定头不含合法 box 边界，
            // 所以 entries 需要显式跳过固定头，见 entry()）
            if (b.type in setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "mvex", "moof", "traf", "dinf")) {
                runCatching { return child(data, b, type) }
            }
        }
        throw NoSuchElementException("no $type in ${parent.type}")
    }
    private fun u32(d: ByteArray, o: Int) =
        (d[o].toInt() and 0xFF shl 24) or (d[o + 1].toInt() and 0xFF shl 16) or
        (d[o + 2].toInt() and 0xFF shl 8) or (d[o + 3].toInt() and 0xFF)
    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF shl 8) or (d[o + 1].toInt() and 0xFF)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (n in nals) { out.write(byteArrayOf(0, 0, 0, 1)); out.write(n) }
        return out.toByteArray()
    }

    /** 造一个 ADTS 帧（AAC-LC，可配采样率/声道；负载填 0xAB）。 */
    private fun adts(payload: Int = 20, freqIdx: Int = 4, chan: Int = 2): ByteArray {
        val len = 7 + payload
        val h = ByteArray(7)
        h[0] = 0xFF.toByte(); h[1] = 0xF1.toByte()
        h[2] = (((2 - 1) shl 6) or (freqIdx shl 2) or (chan ushr 2)).toByte()
        h[3] = (((chan and 3) shl 6) or (len ushr 11)).toByte()
        h[4] = (len ushr 3).toByte()
        h[5] = ((len and 7) shl 5 or 0x1F).toByte()
        h[6] = 0xFC.toByte()
        return h + ByteArray(payload) { 0xAB.toByte() }
    }

    private fun videoMuxer(): Fmp4Muxer = Fmp4Muxer(hasAudio = false).apply { setSpsPps(SPS, PPS) }

    @Test fun `SPS 宽高解析（Baseline 1280x720）`() {
        assertEquals(1280 to 720, Fmp4Muxer.parseSpsSize(SPS))
    }

    @Test fun `init 段结构：ftyp+moov，双轨 avcC 与 esds 内容正确`() {
        val m = Fmp4Muxer(hasAudio = true)
        m.setSpsPps(annexB(SPS), annexB(PPS))           // Annex-B 形态也要能吃
        assertFalse(m.initReady)                        // 音轨缺 ASC → 未就绪
        assertNull(m.initSegment())
        m.writeAudioFrame(adts(freqIdx = 4, chan = 2), 0)
        assertTrue(m.initReady)
        val init = m.initSegment()!!

        val top = boxes(init)
        assertEquals(listOf("ftyp", "moov"), top.map { it.type })
        val moov = top[1]
        assertEquals(listOf("mvhd", "trak", "trak", "mvex"),
            boxes(init, moov.off + 8, moov.off + moov.size).map { it.type })

        // 视频 trak：tkhd 宽高 1280x720(16.16)，mdhd timescale 90000，avc1 1280x720，avcC 内容
        val vTrak = child(init, moov, "trak")
        assertEquals(1280 shl 16, u32(init, child(init, vTrak, "tkhd").off + 8 + 76))
        assertEquals(720 shl 16, u32(init, child(init, vTrak, "tkhd").off + 8 + 80))
        assertEquals(90000, u32(init, child(init, vTrak, "mdhd").off + 8 + 12))
        val stsd = child(init, vTrak, "stsd")
        val avc1 = Box("avc1", stsd.off + 16, u32(init, stsd.off + 16))
        assertEquals(1280, u16(init, avc1.off + 8 + 24))
        assertEquals(720, u16(init, avc1.off + 8 + 26))
        val avcC = child(init, avc1, "avcC")
        val c = avcC.off + 8
        assertEquals(1, init[c].toInt())                          // configurationVersion
        assertEquals(0x42, init[c + 1].toInt() and 0xFF)          // profile Baseline
        assertEquals(0x1F, init[c + 3].toInt() and 0xFF)          // level 3.1
        assertEquals(3, init[c + 4].toInt() and 3)                // 4B 长度前缀
        assertEquals(1, init[c + 5].toInt() and 0x1F)             // 1 个 SPS
        assertEquals(SPS.size, u16(init, c + 6))
        assertArrayEquals(SPS, init.copyOfRange(c + 8, c + 8 + SPS.size))
        val ppsLenAt = c + 8 + SPS.size + 1
        assertEquals(PPS.size, u16(init, ppsLenAt))
        assertArrayEquals(PPS, init.copyOfRange(ppsLenAt + 2, ppsLenAt + 2 + PPS.size))

        // 音频 trak：mdhd timescale 44100，mp4a stereo，esds 含 ASC 0x1210（AAC-LC 44.1 stereo）
        val traks = boxes(init, moov.off + 8, moov.off + moov.size).filter { it.type == "trak" }
        val aTrak = traks[1]
        assertEquals(44100, u32(init, child(init, aTrak, "mdhd").off + 8 + 12))
        val aStsd = child(init, aTrak, "stsd")
        val mp4a = Box("mp4a", aStsd.off + 16, u32(init, aStsd.off + 16))
        assertEquals(2, u16(init, mp4a.off + 8 + 16))             // channelcount
        assertEquals(44100 shl 16, u32(init, mp4a.off + 8 + 24))  // samplerate 16.16
        val esds = child(init, mp4a, "esds")
        val e = esds.off + 8 + 4                                  // 过 fullbox
        assertEquals(0x03, init[e].toInt() and 0xFF)              // ES_Descriptor
        val ascTag = e + 2 + 2 + 1 + 2 + 13                       // tag+len + ES_ID+flags + tag+len + DecConfig(13)
        assertEquals(0x05, init[ascTag].toInt() and 0xFF)
        assertEquals(2, init[ascTag + 1].toInt())                 // ASC 长度
        assertEquals(0x12, init[ascTag + 2].toInt() and 0xFF)     // AAC-LC + 44100 高位
        assertEquals(0x10, init[ascTag + 3].toInt() and 0xFF)     // 44100 低位 + stereo

        // mvex：每轨一个 trex
        val mvex = child(init, moov, "mvex")
        assertEquals(listOf("trex", "trex"), boxes(init, mvex.off + 8, mvex.off + mvex.size).map { it.type })
    }

    @Test fun `纯视频 init 段只有单轨`() {
        val init = videoMuxer().initSegment()!!
        val moov = boxes(init)[1]
        assertEquals(listOf("mvhd", "trak", "mvex"),
            boxes(init, moov.off + 8, moov.off + moov.size).map { it.type })
    }

    @Test fun `fragment：styp+moof+mdat，样本数与时长正确，AVCC 无起始码且剥掉 AUD 与 SPS、PPS`() {
        val m = videoMuxer()
        val idr = byteArrayOf(0x65) + ByteArray(100) { 1 }
        val p1 = byteArrayOf(0x41) + ByteArray(40) { 2 }
        val p2 = byteArrayOf(0x41) + ByteArray(50) { 3 }
        // IDR 帧带 AUD + 带内 SPS/PPS（都应被剥掉）
        m.writeVideoFrame(annexB(byteArrayOf(0x09, 0xF0.toByte()), SPS, PPS, idr), 0, true)
        m.writeVideoFrame(annexB(p1), 33_333, false)
        m.writeVideoFrame(annexB(p2), 66_666, false)
        val frag = m.flushFragment()!!

        val top = boxes(frag)
        assertEquals(listOf("styp", "moof", "mdat"), top.map { it.type })
        val moof = top[1]; val mdat = top[2]
        assertEquals(1, u32(frag, child(frag, moof, "mfhd").off + 8 + 4))   // sequence_number
        val traf = child(frag, moof, "traf")
        // tfhd：flags=default-base-is-moof，track 1
        val tfhd = child(frag, traf, "tfhd")
        assertEquals(0x020000, u32(frag, tfhd.off + 8) and 0xFFFFFF)
        assertEquals(1, u32(frag, tfhd.off + 12))
        // tfdt v1：base=0（首帧 pts 0）
        val tfdt = child(frag, traf, "tfdt")
        assertEquals(1, frag[tfdt.off + 8].toInt())
        assertEquals(0, u32(frag, tfdt.off + 12)); assertEquals(0, u32(frag, tfdt.off + 16))
        // trun：3 样本，时长 2999 tick（33333us×90/1000 截断），末样本沿用前一帧差值
        val trun = child(frag, traf, "trun")
        assertEquals(0x701, u32(frag, trun.off + 8) and 0xFFFFFF)
        assertEquals(3, u32(frag, trun.off + 12))
        val dataOff = u32(frag, trun.off + 16)
        var s = trun.off + 20
        val sizes = listOf(4 + idr.size, 4 + p1.size, 4 + p2.size)
        for (i in 0..2) {
            assertEquals(2999, u32(frag, s))
            assertEquals(sizes[i], u32(frag, s + 4))
            assertEquals(if (i == 0) 0x02000000 else 0x01010000, u32(frag, s + 8))
            s += 12
        }
        // data_offset 指向 mdat 负载起点（default-base-is-moof → 相对 moof 起点）
        assertEquals(mdat.off - moof.off + 8, dataOff)
        // mdat：AVCC（4B 长度 + NAL），无 Annex-B 起始码、无 AUD(09)/SPS(67)/PPS(68)
        val payload = frag.copyOfRange(mdat.off + 8, mdat.off + mdat.size)
        assertEquals(sizes.sum(), payload.size)
        assertEquals(idr.size, u32(payload, 0))
        assertEquals(0x65, payload[4].toInt() and 0xFF)
        assertFalse(payload.toHexString().contains("00000001"))
        assertFalse(payload.toHexString().contains("000001"))
    }

    @Test fun `fragment 间 tfdt 连续（各轨 pts 基准）`() {
        val m = videoMuxer()
        val f = byteArrayOf(0x41) + ByteArray(10)
        m.writeVideoFrame(annexB(f), 0, false)
        m.flushFragment()
        m.writeVideoFrame(annexB(f), 1_000_000, false)   // 1s 后 → tfdt=90000
        val frag = m.flushFragment()!!
        val moof = boxes(frag)[1]
        val tfdt = child(frag, child(frag, moof, "traf"), "tfdt")
        assertEquals(0, u32(frag, tfdt.off + 12))
        assertEquals(90000, u32(frag, tfdt.off + 16))
        // mfhd 序号递增
        assertEquals(2, u32(frag, child(frag, moof, "mfhd").off + 8 + 4))
    }

    @Test fun `音视频混合 fragment：双 traf，音频剥 ADTS 头、时长 1024`() {
        val m = Fmp4Muxer(hasAudio = true)
        m.setSpsPps(SPS, PPS)
        val v = byteArrayOf(0x65) + ByteArray(30)
        m.writeVideoFrame(annexB(v), 0, true)
        m.writeAudioFrame(adts(payload = 20), 0)
        m.writeAudioFrame(adts(payload = 20), 23_220)
        val frag = m.flushFragment()!!

        val moof = boxes(frag)[1]
        val trafs = boxes(frag, moof.off + 8, moof.off + moof.size).filter { it.type == "traf" }
        assertEquals(2, trafs.size)
        val aTrun = child(frag, trafs[1], "trun")
        assertEquals(2, u32(frag, aTrun.off + 12))
        var s = aTrun.off + 20
        repeat(2) {
            assertEquals(1024, u32(frag, s))
            assertEquals(20, u32(frag, s + 4))            // 已剥 7B ADTS 头
            assertEquals(0x02000000, u32(frag, s + 8))
            s += 12
        }
        // 音频 traf 的 data_offset 指向视频样本之后
        val vTrun = child(frag, trafs[0], "trun")
        assertEquals(u32(frag, vTrun.off + 16) + 4 + v.size, u32(frag, aTrun.off + 16))
    }

    @Test fun `空 flush 返回 null`() {
        assertNull(videoMuxer().flushFragment())
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}
