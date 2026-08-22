// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

/**
 * 最小 MPEG-TS 封包器（纯 Kotlin、无 Android 依赖，JVM 可单测）。
 *
 * 把 H.264 Annex-B access unit 打包成 188B TS 包流，供 LiveAudioHttpServer 以
 * `video/mp2t` 扇出给 DLNA 渲染器（坚果等）做实时屏幕镜像播放。
 *
 * 结构：
 *  - PAT（PMT pid=[pmtPid]）+ PMT（stream_type 0x1B=H.264）周期性重发（每 [patPmtIntervalPackets] 个 TS 包），
 *    保证播放器中途接入也能拿到节目表。
 *  - PES：stream_id 0xE0，PTS=DTS（编码器须 Baseline 无 B 帧，无重排），PES_packet_length=0（视频允许无限长）。
 *  - PCR：每个 PES 的首个 TS 包 adaptation field 带 PCR（27MHz 体系，ext=0），时钟 = PTS(90kHz)。
 *  - SPS/PPS：调用方通过 [setSpsPps] 灌入；每个关键帧前自动前置，保证中途接入可解码。
 *  - 尾部不足 184B 的 TS 包用 adaptation field stuffing（0xFF）补齐。
 *
 * 第二阶段（声画同投）：构造传 [audioPid] 后 PMT 会加 AAC(0x0F) 流，用 [writeAudioFrame] 喂 AAC 裸帧。
 */
class TsMuxer(
    private val videoPid: Int = 0x101,
    private val pmtPid: Int = 0x1000,
    private val audioPid: Int? = null,
    private val patPmtIntervalPackets: Int = 40,
    private val onPacket: (ByteArray) -> Unit,
) {
    private val cc = HashMap<Int, Int>()          // continuity counter，按 PID
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var packetsSincePatPmt = Int.MAX_VALUE // 强制首帧前先发 PAT/PMT

    /** 灌入编码器输出的 SPS/PPS（Annex-B，含 00 00 00 01 起始码）。关键帧前自动重发。 */
    fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        this.sps = sps; this.pps = pps
    }

    /** 喂一个 H.264 access unit（Annex-B，不含 SPS/PPS；关键帧自动前置 SPS/PPS）。ptsUs 为微秒呈现时间。 */
    fun writeVideoFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        val pts90 = usTo90k(ptsUs)
        maybePatPmt()
        val payload = if (keyframe) {
            val s = sps; val p = pps
            if (s != null && p != null) s + p + data else data
        } else data
        writePes(videoPid, streamId = 0xE0, payload = payload, pts90 = pts90, withPcr = true)
    }

    /** 第二阶段：喂一个 AAC-LC 裸帧（去 ADTS 头）。 */
    fun writeAudioFrame(data: ByteArray, ptsUs: Long) {
        val pid = audioPid ?: return
        maybePatPmt()
        writePes(pid, streamId = 0xC0, payload = data, pts90 = usTo90k(ptsUs), withPcr = false)
    }

    private fun maybePatPmt() {
        if (packetsSincePatPmt < patPmtIntervalPackets) return
        emitPsi(tableId = 0x00, body = buildPatBody(), pid = 0x0000)
        emitPsi(tableId = 0x02, body = buildPmtBody(), pid = pmtPid)
        packetsSincePatPmt = 0
    }

    // ---- PSI ----
    private fun buildPatBody(): ByteArray {
        // transport_stream_id(2) + flags(1) + section_number(1) + last_section_number(1) + program(4)
        val b = ByteArray(9)
        put16(b, 0, 1)                                  // transport_stream_id
        b[2] = 0xC1.toByte()                            // reserved11 + current_next=1
        // section_number=0, last_section_number=0 默认 0
        put16(b, 5, 1)                                  // program_number 1
        put16(b, 7, 0xE000 or pmtPid)                   // reserved111 + PMT PID
        return b
    }

    private fun buildPmtBody(): ByteArray {
        // program_number(2) + flags(1) + section(1) + last(1) + PCR_PID(2) + program_info_length(2) + streams
        val streams = mutableListOf<ByteArray>()
        streams += streamEntry(0x1B, videoPid)          // H.264
        audioPid?.let { streams += streamEntry(0x0F, it) } // AAC ADTS
        val bodyLen = 9 + streams.sumOf { it.size }
        val b = ByteArray(bodyLen)
        put16(b, 0, 1)                                  // program_number
        b[2] = 0xC1.toByte()
        put16(b, 5, 0xE000 or videoPid)                 // PCR_PID = 视频 PID
        put16(b, 7, 0xF000)                             // program_info_length = 0
        var off = 9
        for (s in streams) { s.copyInto(b, off); off += s.size }
        return b
    }

    private fun streamEntry(type: Int, pid: Int): ByteArray {
        val e = ByteArray(5)
        e[0] = type.toByte()
        put16(e, 1, 0xE000 or pid)                      // elementary PID
        put16(e, 3, 0xF000)                             // ES_info_length = 0
        return e
    }

    /** PSI section：table_id + section_length + body + CRC32(MPEG-2)，包进一个 TS 包（PUSI=1, pointer=0）。 */
    private fun emitPsi(tableId: Int, body: ByteArray, pid: Int) {
        val sectionLength = body.size + 4               // body + CRC32
        val sec = ByteArray(3 + sectionLength)
        sec[0] = tableId.toByte()
        put16(sec, 1, 0xB000 or sectionLength)          // section_syntax_indicator=1
        body.copyInto(sec, 3)
        val crc = crc32Mpeg(sec, 0, 3 + body.size)
        put32(sec, 3 + body.size, crc.toInt())
        // PSI 永远一个 TS 包：payload = pointer_field(0x00) + section，尾部 stuffing
        val payload = ByteArray(1 + sec.size)
        sec.copyInto(payload, 1)
        sendTs(pid, payloadUnitStart = true, payload = payload, pcr90 = null)
    }

    // ---- PES ----
    private fun writePes(pid: Int, streamId: Int, payload: ByteArray, pts90: Long, withPcr: Boolean) {
        // PES 头：00 00 01 + stream_id + packet_length(0=不限) + '10'flags + PTS
        val pes = ByteArray(9 + 5 + payload.size)
        pes[2] = 1                                      // start_code_prefix 00 00 01（前两位默认 0）
        pes[3] = streamId.toByte()
        put16(pes, 4, 0)                                // PES_packet_length = 0（视频无限长流惯例）
        pes[6] = 0x80.toByte()                          // '10' + 无加扰 + 无优先级
        pes[7] = 0x80.toByte()                          // PTS only
        pes[8] = 5                                      // header_data_length
        writePts(pes, 9, pts90, 0x2)                    // '0010' + PTS
        payload.copyInto(pes, 14)

        var off = 0
        var first = true
        while (off < pes.size) {
            // 首包可带 PCR：adaptation 占 8B（len+flags+6B PCR），payload 容量减到 176
            val pcr = if (first && withPcr) pts90 else null
            val capacity = 184 - (if (pcr != null) 8 else 0)
            val n = minOf(capacity, pes.size - off)
            val chunk = pes.copyOfRange(off, off + n)
            sendTs(pid, payloadUnitStart = first, payload = chunk, pcr90 = pcr)
            off += n
            first = false
        }
    }

    // ---- TS 包 ----
    private fun sendTs(pid: Int, payloadUnitStart: Boolean, payload: ByteArray, pcr90: Long?) {
        require(payload.size <= 184) { "payload too big: ${payload.size}" }
        val pkt = ByteArray(188)
        pkt[0] = 0x47
        put16(pkt, 1, (if (payloadUnitStart) 0x4000 else 0) or pid)
        val stuffing = 184 - payload.size - (if (pcr90 != null) 8 else 0)
        val hasAdaptation = pcr90 != null || stuffing > 0
        pkt[3] = ((if (hasAdaptation) 0x30 else 0x10) or nextCc(pid)).toByte()
        var off = 4
        if (hasAdaptation) {
            val aflStart = off + 1
            var afl = 0
            if (pcr90 != null) {
                pkt[off + 1] = 0x10                     // PCR_flag
                writePcr(pkt, off + 2, pcr90)
                afl += 7
            }
            val stuffBytes = 183 - afl - payload.size
            check(stuffBytes >= 0) { "TS overflow: payload=${payload.size} afl=$afl" }
            for (i in 0 until stuffBytes) pkt[aflStart + afl + i] = 0xFF.toByte()
            afl += stuffBytes
            pkt[off] = afl.toByte()
            off += 1 + afl
        }
        payload.copyInto(pkt, off)
        packetsSincePatPmt++
        onPacket(pkt)
    }

    private fun nextCc(pid: Int): Int {
        val v = (cc[pid] ?: 0) and 0xF
        cc[pid] = (v + 1) and 0xF
        return v
    }

    private fun writePcr(dst: ByteArray, off: Int, pcr90: Long) {
        val base = pcr90 and 0x1FFFFFFFFL
        dst[off] = (base ushr 25).toByte()
        dst[off + 1] = (base ushr 17).toByte()
        dst[off + 2] = (base ushr 9).toByte()
        dst[off + 3] = (base ushr 1).toByte()
        dst[off + 4] = ((base and 1) shl 7 or 0x7E).toByte()  // base lsb + reserved(111111)
        dst[off + 5] = 0                                      // ext = 0
    }

    /** PTS 的 5 字节编码：prefix(4bit) + PTS(33bit, 分 3 段) + marker bits。 */
    private fun writePts(dst: ByteArray, off: Int, pts: Long, prefix: Int) {
        val v = pts and 0x1FFFFFFFFL
        dst[off] = (prefix shl 4 or ((v ushr 30).toInt() and 7) shl 1 or 1).toByte()
        dst[off + 1] = (v ushr 22).toByte()
        dst[off + 2] = (((v ushr 15).toInt() and 0x7F) shl 1 or 1).toByte()
        dst[off + 3] = (v ushr 7).toByte()
        dst[off + 4] = (((v and 0x7F).toInt()) shl 1 or 1).toByte()
    }

    private fun usTo90k(us: Long): Long = us * 9 / 100  // 90kHz：1s=90000 ticks；会话时长级 Long 不溢出

    companion object {
        private fun put16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
        }

        private fun put32(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
        }

        /** CRC32/MPEG-2：poly 0x04C11DB7，init 0xFFFFFFFF，无反射，xorout 0。 */
        fun crc32Mpeg(data: ByteArray, off: Int, len: Int): Long {
            var crc = 0xFFFFFFFFL
            for (i in off until off + len) {
                crc = crc xor ((data[i].toLong() and 0xFF) shl 24)
                repeat(8) {
                    crc = if (crc and 0x80000000L != 0L) (crc shl 1) xor 0x04C11DB7L else crc shl 1
                    crc = crc and 0xFFFFFFFFL
                }
            }
            return crc
        }
    }
}
