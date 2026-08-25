// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import java.io.ByteArrayOutputStream

/**
 * 最小 fMP4 封包器（纯 Kotlin、无 Android 依赖，JVM 可单测）——LL-HLS 的 fMP4 容器路线。
 *
 * 背景：AirPlay 接收端（AVPlayer）拒收 MPEG-TS 容器上的 EXT-X-PART 小片（实测永远 loading），
 * Apple LL-HLS 的小片原生形态是 fMP4 fragment。结构对照 /tmp/llref（ffmpeg 参考流逐字节解剖）：
 *  - init 段：ftyp + moov（视频 trak: avc1+avcC；可选音频 trak: mp4a+esds；mvex 每轨 trex）。
 *  - 每个小片 = 一个独立 fragment：styp + moof(mfhd + 每轨 traf(tfhd+tfdt+trun)) + mdat。
 *    分片（segment）= 若干小片 fragment 顺序拼接，本身即是合法多 fragment fMP4。
 *
 * 简化假设（与编码器配置绑定，勿随意放宽）：
 *  - 视频 Baseline H.264、无 B 帧 → PTS=DTS，不写 ctts；Annex-B → AVCC（4B 大端长度前缀，
 *    剥 AUD/SPS/PPS NAL——SPS/PPS 已进 avcC，AUD 无 fMP4 对应物）。
 *  - 音频 AAC-LC ADTS → 剥 7/9B ADTS 头，首帧解析出 AudioSpecificConfig 进 esds。
 *  - tfhd 用 default-base-is-moof(0x020000)：trun data_offset 相对 moof 起点，无需全局偏移簿记。
 *  - timescale：视频 90000、音频取 ADTS 采样率（44100）；tfdt baseMediaDecodeTime = 各轨
 *    本 fragment 首样本的归零 pts（与 TS 路径同基，HLS 模式调用方已含 +1s 偏移惯例）。
 */
class Fmp4Muxer(
    /** true=声明 AAC 音轨（init 含第二个 trak）；init 需等首个 ADTS 帧解析出 ASC 才算就绪。 */
    private val hasAudio: Boolean = false,
) {
    private var sps: ByteArray? = null          // 裸 NAL（无起始码）
    private var pps: ByteArray? = null
    private var videoW = 0
    private var videoH = 0
    private var audioAsc: ByteArray? = null     // AudioSpecificConfig（来自首帧 ADTS）
    private var audioSampleRate = 44100
    private var audioChannels = 2

    private class VSample(val avcc: ByteArray, val ptsUs: Long, val key: Boolean)
    private class ASample(val raw: ByteArray, val ptsUs: Long)
    private val vSamples = ArrayList<VSample>()
    private val aSamples = ArrayList<ASample>()
    private var mfhdSeq = 1
    private var cachedInit: ByteArray? = null

    /** init 段可生成的条件：SPS/PPS 已灌入；有音轨时首帧 ADTS 已到（ASC 已解析）。 */
    val initReady: Boolean
        @Synchronized get() = sps != null && (!hasAudio || audioAsc != null)

    /** 灌入 SPS/PPS（Annex-B 或裸 NAL 均可）；从 SPS 解出宽高写进 avc1/tkhd。 */
    @Synchronized
    fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        this.sps = stripStartCode(sps)
        this.pps = stripStartCode(pps)
        val (w, h) = parseSpsSize(this.sps!!)
        videoW = w; videoH = h
        cachedInit = null
    }

    /** init 段（ftyp+moov）。[initReady] 为 false 时返回 null（等配置，勿发 404 以外的半成品）。 */
    @Synchronized
    fun initSegment(): ByteArray? {
        if (!initReady) return null
        cachedInit?.let { return it }
        val s = sps!!; val p = pps!!
        val asc = if (hasAudio) audioAsc!! else null
        val out = ByteArrayOutputStream()
        // ftyp：与 ffmpeg 参考流同形（major iso5, minor 0x200, brands iso5/iso6/mp41）
        out.write(box("ftyp", "iso5".toByteArray() + u32(0x200) +
            "iso5".toByteArray() + "iso6".toByteArray() + "mp41".toByteArray()))
        val moov = ByteArrayOutputStream()
        moov.write(mvhd(if (hasAudio) 3 else 2))
        moov.write(videoTrak(s, p))
        if (hasAudio) moov.write(audioTrak(asc!!))
        moov.write(mvex(if (hasAudio) 2 else 1))
        out.write(box("moov", moov.toByteArray()))
        return out.toByteArray().also { cachedInit = it }
    }

    /** 喂一个 H.264 access unit（Annex-B，可含/不含 AUD 与带内 SPS/PPS——都会被剥掉）。 */
    @Synchronized
    fun writeVideoFrame(annexB: ByteArray, ptsUs: Long, keyframe: Boolean) {
        val out = ByteArrayOutputStream()
        for (nal in splitNals(annexB)) {
            if (nal.isEmpty()) continue
            val type = nal[0].toInt() and 0x1F
            if (type == 9 || type == 7 || type == 8) continue   // AUD/SPS/PPS：不进样本
            out.write(u32(nal.size)); out.write(nal)
        }
        if (out.size() > 0) vSamples.add(VSample(out.toByteArray(), ptsUs, keyframe))
    }

    /** 喂一个 AAC 帧（带 ADTS 头）；剥头存裸负载，首帧解析 ASC。非音轨模式直接丢弃。 */
    @Synchronized
    fun writeAudioFrame(adtsFrame: ByteArray, ptsUs: Long) {
        if (!hasAudio || adtsFrame.size < 8) return
        if ((adtsFrame[0].toInt() and 0xFF) != 0xFF || (adtsFrame[1].toInt() and 0xF0) != 0xF0) return
        val protectionAbsent = adtsFrame[1].toInt() and 1
        val hdrLen = if (protectionAbsent == 1) 7 else 9
        val frameLen = ((adtsFrame[3].toInt() and 3) shl 11) or
            ((adtsFrame[4].toInt() and 0xFF) shl 3) or ((adtsFrame[5].toInt() ushr 5) and 7)
        if (frameLen < hdrLen || frameLen > adtsFrame.size) return
        if (audioAsc == null) {
            val profile = ((adtsFrame[2].toInt() ushr 6) and 3) + 1   // ADTS profile → objectType
            val freqIdx = (adtsFrame[2].toInt() ushr 2) and 0xF
            val chan = ((adtsFrame[2].toInt() and 1) shl 2) or ((adtsFrame[3].toInt() ushr 6) and 3)
            audioAsc = byteArrayOf(
                ((profile shl 3) or (freqIdx ushr 1)).toByte(),
                (((freqIdx and 1) shl 7) or (chan shl 3)).toByte())
            audioSampleRate = SAMPLE_RATES.getOrElse(freqIdx) { 44100 }
            audioChannels = if (chan == 0) 2 else chan
            cachedInit = null
        }
        aSamples.add(ASample(adtsFrame.copyOfRange(hdrLen, frameLen), ptsUs))
    }

    /**
     * 关闭当前 fragment（墙钟小片切点调用）：已积累样本打成 styp+moof+mdat 返回；
     * 无样本返回 null。每个 traf 带 tfdt（= 本 fragment 该轨首样本 pts），片段间时间轴连续。
     */
    @Synchronized
    fun flushFragment(): ByteArray? {
        if (vSamples.isEmpty() && aSamples.isEmpty()) return null
        val vs = vSamples.toList(); vSamples.clear()
        val asmp = aSamples.toList(); aSamples.clear()

        val mdatV = concat(vs.map { it.avcc })
        val mdatA = concat(asmp.map { it.raw })
        val moof = ByteArrayOutputStream()
        moof.write(box("mfhd", full(0, 0) + u32(mfhdSeq++)))
        val dataOffsetPatches = ArrayList<Pair<Int, Int>>()   // (moof 内补丁位置, 轨道 mdat 内基址)
        var mdatTrackBase = 0
        if (vs.isNotEmpty()) {
            val base90 = vs.first().ptsUs * 90 / 1000
            val durs = LongArray(vs.size) { i ->
                if (i + 1 < vs.size) maxOf(1, (vs[i + 1].ptsUs - vs[i].ptsUs) * 90 / 1000)
                else if (i > 0) maxOf(1, (vs[i].ptsUs - vs[i - 1].ptsUs) * 90 / 1000) else 3000
            }
            dataOffsetPatches += moof.size() + TRAF_DATA_OFFSET_POS to mdatTrackBase
            moof.write(traf(trackId = 1, tfdt = base90,
                sizes = vs.map { it.avcc.size }, durs = durs.toList(),
                flags = vs.map { if (it.key) SAMPLE_FLAG_SYNC else SAMPLE_FLAG_NON_SYNC }))
            mdatTrackBase += mdatV.size
        }
        if (asmp.isNotEmpty()) {
            val baseA = asmp.first().ptsUs * audioSampleRate.toLong() / 1_000_000
            dataOffsetPatches += moof.size() + TRAF_DATA_OFFSET_POS to mdatTrackBase
            moof.write(traf(trackId = 2, tfdt = baseA,
                sizes = asmp.map { it.raw.size }, durs = List(asmp.size) { 1024L },
                flags = List(asmp.size) { SAMPLE_FLAG_SYNC }))
        }
        val moofBytes = box("moof", moof.toByteArray())
        // trun data_offset：相对 moof 起点（tfhd default-base-is-moof）→ moof 长 + mdat 头(8) + 轨内基址
        for ((pos, trackBase) in dataOffsetPatches)
            u32(moofBytes.size + 8 + trackBase).copyInto(moofBytes, pos + 8)  // +8: box("moof") 头
        return box("styp", "msdh".toByteArray() + u32(0) +
            "msdh".toByteArray() + "msix".toByteArray()) + moofBytes + box("mdat", mdatV + mdatA)
    }

    // ---- traf：tfhd(default-base-is-moof) + tfdt(v1, u64) + trun(duration+size+flags+data_offset) ----
    private fun traf(trackId: Int, tfdt: Long, sizes: List<Int>, durs: List<Long>, flags: List<Long>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(box("tfhd", full(0, 0x020000) + u32(trackId)))                    // fullbox v0
        body.write(box("tfdt", full(1, 0) + u32((tfdt ushr 32).toInt()) + u32(tfdt.toInt())))  // v1
        val trun = ByteArrayOutputStream()
        trun.write(full(0, TRUN_FLAGS))                                              // fullbox v0
        trun.write(u32(sizes.size))
        trun.write(u32(0))                                                       // data_offset 占位（后续补丁）
        for (i in sizes.indices) {
            trun.write(u32(durs[i].toInt()))
            trun.write(u32(sizes[i]))
            trun.write(u32(flags[i].toInt()))
        }
        body.write(box("trun", trun.toByteArray()))
        return box("traf", body.toByteArray())
    }

    // ---- init 段各 box ----
    private fun mvhd(nextTrackId: Int): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(full(0, 0)); b.write(u32(0)); b.write(u32(0))                        // v0 + ctime + mtime
        b.write(u32(1000)); b.write(u32(0))                                      // timescale / duration
        b.write(u32(0x00010000)); b.write(u16(0x0100)); b.write(u16(0))          // rate / volume / reserved
        b.write(u32(0)); b.write(u32(0))                                         // reserved
        b.write(UNITY_MATRIX); b.write(ByteArray(24))                            // matrix / pre_defined
        b.write(u32(nextTrackId))
        return box("mvhd", b.toByteArray())
    }

    private fun tkhd(trackId: Int, width: Int, height: Int, isAudio: Boolean): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(full(0, 0x000003))                                                   // v0 + enabled|in_movie
        b.write(u32(0)); b.write(u32(0))                                         // ctime / mtime
        b.write(u32(trackId)); b.write(u32(0)); b.write(u32(0))                  // id / reserved / duration
        b.write(u32(0)); b.write(u32(0))                                         // reserved
        b.write(u16(0)); b.write(u16(0))                                         // layer / alternate_group
        b.write(u16(if (isAudio) 0x0100 else 0)); b.write(u16(0))                // volume / reserved
        b.write(UNITY_MATRIX)
        b.write(u32(width shl 16)); b.write(u32(height shl 16))                  // 16.16 定点
        return box("tkhd", b.toByteArray())
    }

    private fun mdhd(timescale: Int): ByteArray =
        box("mdhd", full(0, 0) + u32(0) + u32(0) + u32(timescale) + u32(0) + u16(0x55C4) + u16(0))

    private fun hdlr(type: String, name: String): ByteArray =
        box("hdlr", full(0, 0) + u32(0) + type.toByteArray() + ByteArray(12) + (name + "\u0000").toByteArray())

    private fun dinf(): ByteArray =
        box("dinf", box("dref", full(0, 0) + u32(1) + box("url ", full(0, 1))))

    private fun emptyStblEntries(): ByteArray =
        box("stts", full(0, 0) + u32(0)) + box("stsc", full(0, 0) + u32(0)) +
        box("stsz", full(0, 0) + u32(0) + u32(0)) + box("stco", full(0, 0) + u32(0))

    private fun videoTrak(sps: ByteArray, pps: ByteArray): ByteArray {
        // avcC：profile/compat/level 直接取 SPS 头三字节；lengthSizeMinusOne=3（4B 长度前缀）
        val avcC = box("avcC", byteArrayOf(1, sps[1], sps[2], sps[3], 0xFF.toByte(), 0xE1.toByte()) +
            u16(sps.size) + sps + byteArrayOf(1) + u16(pps.size) + pps)
        val avc1 = ByteArrayOutputStream()
        avc1.write(ByteArray(6)); avc1.write(u16(1))                             // reserved + data_ref
        avc1.write(ByteArray(16))                                                // pre_defined/reserved
        avc1.write(u16(videoW)); avc1.write(u16(videoH))
        avc1.write(u32(0x00480000)); avc1.write(u32(0x00480000))                 // 72dpi
        avc1.write(u32(0)); avc1.write(u16(1))                                   // reserved / frame_count
        avc1.write(byteArrayOf(8) + "AirSonic".toByteArray() + ByteArray(23))    // compressorname(Pascal, 32B)
        avc1.write(u16(0x0018)); avc1.write(u16(0xFFFF.toInt()))                 // depth / pre_defined
        avc1.write(avcC)
        avc1.write(box("pasp", u32(1) + u32(1)))
        val stbl = box("stbl", box("stsd", full(0, 0) + u32(1) + box("avc1", avc1.toByteArray())) + emptyStblEntries())
        val minf = box("minf",
            box("vmhd", full(0, 1) + u16(0) + u16(0) + u16(0) + u16(0)) + dinf() + stbl)
        val mdia = box("mdia", mdhd(90000) + hdlr("vide", "VideoHandler") + minf)
        return box("trak", tkhd(1, videoW, videoH, isAudio = false) + mdia)
    }

    private fun audioTrak(asc: ByteArray): ByteArray {
        // esds：ES_Descriptor(03) → DecoderConfig(04, objectType 0x40=AAC, streamType 0x15) → ASC(05) → SL(06)
        val decSpecific = byteArrayOf(0x05, asc.size.toByte()) + asc
        val decConfig = byteArrayOf(0x04, 13, 0x40, 0x15) + u24(0) + u32(0) + u32(0)
        val esDesc = byteArrayOf(0x03, (3 + decConfig.size + decSpecific.size + 3).toByte()) +
            u16(2) + byteArrayOf(0) + decConfig + decSpecific + byteArrayOf(0x06, 1, 2)
        val esds = box("esds", full(0, 0) + esDesc)
        val mp4a = ByteArrayOutputStream()
        mp4a.write(ByteArray(6)); mp4a.write(u16(1))                             // reserved + data_ref
        mp4a.write(ByteArray(8))                                                 // version/revlevel/vendor
        mp4a.write(u16(audioChannels)); mp4a.write(u16(16))                      // channels / samplesize
        mp4a.write(u16(0)); mp4a.write(u16(0))                                   // pre_defined / reserved
        mp4a.write(u32(audioSampleRate shl 16))
        mp4a.write(esds)
        val stbl = box("stbl", box("stsd", full(0, 0) + u32(1) + box("mp4a", mp4a.toByteArray())) + emptyStblEntries())
        val minf = box("minf", box("smhd", full(0, 0) + u16(0) + u16(0)) + dinf() + stbl)
        val mdia = box("mdia", mdhd(audioSampleRate) + hdlr("soun", "SoundHandler") + minf)
        return box("trak", tkhd(2, 0, 0, isAudio = true) + mdia)
    }

    private fun mvex(trackCount: Int): ByteArray {
        val b = ByteArrayOutputStream()
        for (id in 1..trackCount)
            b.write(box("trex", full(0, 0) + u32(id) + u32(1) + u32(0) + u32(0) + u32(0)))
        return box("mvex", b.toByteArray())
    }

    // ---- 基础工具 ----
    private fun stripStartCode(nal: ByteArray): ByteArray = when {
        nal.size > 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte() ->
            nal.copyOfRange(4, nal.size)
        nal.size > 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte() ->
            nal.copyOfRange(3, nal.size)
        else -> nal
    }

    private fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()   // (起始码位置, 起始码长度)
        var i = 0
        while (i + 3 <= data.size) {
            val is4 = i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val is3 = !is4 && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (is3 || is4) { starts.add(i to (if (is4) 4 else 3)); i += if (is4) 4 else 3 } else i++
        }
        val out = ArrayList<ByteArray>()
        for (k in starts.indices) {
            val from = starts[k].first + starts[k].second
            val to = if (k + 1 < starts.size) starts[k + 1].first else data.size
            if (to > from) out += data.copyOfRange(from, to)
        }
        if (out.isEmpty() && starts.isEmpty()) out += data   // 无起始码：整体当一个裸 NAL
        return out
    }

    companion object {
        /** trun：data-offset(0x1) + sample-duration(0x100) + sample-size(0x200) + sample-flags(0x400)。 */
        private const val TRUN_FLAGS = 0x000701
        /** traf 起点到 trun data_offset 字段的固定距离：traf头8 + tfhd16 + tfdt20 + trun(8头+4flags+4count)。 */
        private const val TRAF_DATA_OFFSET_POS = 8 + 16 + 20 + 16
        /** sample_flags：sync=sample_depends_on(2)；non-sync=depends_on(1)+is_non_sync(1)。 */
        private const val SAMPLE_FLAG_SYNC = 0x02000000L
        private const val SAMPLE_FLAG_NON_SYNC = 0x01010000L
        private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350)
        private val UNITY_MATRIX = u32(0x00010000) + u32(0) + u32(0) +
            u32(0) + u32(0x00010000) + u32(0) + u32(0) + u32(0) + u32(0x40000000)

        private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        private fun u24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        /** fullbox 头：version(1B) + flags(3B)。 */
        private fun full(version: Int, flags: Int) = byteArrayOf(version.toByte()) + u24(flags)
        private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(),
            (v ushr 8).toByte(), v.toByte())
        private fun box(type: String, body: ByteArray): ByteArray =
            u32(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
        private fun concat(parts: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream(); for (p in parts) out.write(p); return out.toByteArray()
        }

        /** 从裸 SPS NAL 解 (宽, 高)：Baseline/Main/High 通用（含 scaling list 跳过与帧裁剪）。 */
        fun parseSpsSize(sps: ByteArray): Pair<Int, Int> {
            // 去 emulation prevention（00 00 03 → 00 00）
            val rbsp = ByteArrayOutputStream()
            var zeros = 0
            for (i in 1 until sps.size) {          // 跳过 NAL 头字节
                val b = sps[i].toInt() and 0xFF
                if (zeros >= 2 && b == 3) { zeros = 0; continue }
                zeros = if (b == 0) zeros + 1 else 0
                rbsp.write(b)
            }
            val br = BitReader(rbsp.toByteArray())
            val profileIdc = br.u(8); br.u(8); br.u(8)
            br.ue()                                                                 // sps id
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chromaFormat = br.ue()
                if (chromaFormat == 3) br.u(1)
                br.ue(); br.ue(); br.u(1)
                if (br.u(1) == 1) {                                                 // seq_scaling_matrix_present
                    for (i in 0 until if (chromaFormat != 3) 8 else 12) {
                        if (br.u(1) == 1) {                                         // scaling list present
                            val size = if (i < 6) 16 else 64
                            var lastScale = 8; var nextScale = 8
                            for (j in 0 until size) {
                                if (nextScale != 0) {
                                    nextScale = (lastScale + br.se() + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }
            br.ue()                                                                 // log2_max_frame_num_minus4
            val pocType = br.ue()
            if (pocType == 0) br.ue()
            else if (pocType == 1) {
                br.u(1); br.se(); br.se()
                val n = br.ue(); repeat(n) { br.se() }
            }
            br.ue(); br.u(1)                                                        // max_num_ref_frames / gaps
            val wMbs = br.ue() + 1
            val hMapUnits = br.ue() + 1
            val frameMbsOnly = br.u(1)
            if (frameMbsOnly == 0) br.u(1)
            br.u(1)                                                                 // direct_8x8_inference
            var cropX = 0; var cropY = 0
            if (br.u(1) == 1) {                                                     // frame_cropping
                cropX = br.ue() + br.ue()                                           // left + right
                cropY = br.ue() + br.ue()                                           // top + bottom
            }
            // 4:2:0 非 3 分量：crop unit x=2；y=2（帧）/4（场编码）
            val subHeightC = if (frameMbsOnly == 1) 1 else 2
            val width = wMbs * 16 - cropX * 2
            val height = hMapUnits * 16 * (2 - frameMbsOnly) - cropY * 2 * subHeightC
            require(width > 0 && height > 0) { "SPS 尺寸解析失败: ${width}x$height" }
            return width to height
        }

        private class BitReader(private val data: ByteArray) {
            private var pos = 0
            fun u(n: Int): Int {
                var v = 0
                repeat(n) {
                    v = (v shl 1) or ((data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1)
                    pos++
                }
                return v
            }
            fun ue(): Int {
                var zeros = 0
                while (u(1) == 0 && zeros < 32) zeros++
                return (1 shl zeros) - 1 + u(zeros)
            }
            fun se(): Int {
                val k = ue()
                return if (k and 1 == 0) -(k ushr 1) else (k + 1) ushr 1
            }
        }
    }
}
