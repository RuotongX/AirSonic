// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

/**
 * AirPlay 2 / RAOP 时钟与 RTP 时间轴（严格对齐 pyatv：
 * pyatv/protocols/raop/timing.py + protocols/__init__.py StreamContext）。
 *
 * 这是「出声」的关键：真设备（HomePod/Sonos）在 timing 模式下需要一个
 * 共享时钟来决定每个 RTP timestamp 何时播放。少了它，接收端的
 * anchorRTPTimestamp 永远为空，msec_to_playout 恒返回 0 → 静音。
 *
 * 单一事实来源：音频发送器推进 [headTs]，Sync 发送器读取 [rtptime] 与
 * [syncNtp]，两者共享同一个 [AirplayClock] 实例。
 *
 * NTP 纪元偏移 0x83AA7E80 = 1900→1970 的秒数（2208988800）。
 */
class AirplayClock(
    val sampleRate: Int = 44100,
    val channels: Int = 2
) {
    /** 与 pyatv 一致：latency = 22050 + sample_rate。 */
    val latency: Long = 22050L + sampleRate

    /** 流起点 RTP 时间戳（reset 时按当前 NTP 锚定）。 */
    @Volatile var startTs: Long = 0L
        private set

    /** 当前已推进到的 RTP 时间戳（每发一包前进 framesPerPacket）。 */
    @Volatile var headTs: Long = 0L
        private set

    init { reset() }

    /** 重置时间轴。采样率变化或开始新流时调用。 */
    fun reset() {
        startTs = ntp2ts(ntpNow(), sampleRate)
        headTs = startTs
    }

    /**
     * 当前 RTP time（含 latency 偏移），用于 RTP 包头 timestamp 与 SyncPacket.now。
     * 对齐 pyatv：rtptime = head_ts - (start_ts - latency)。
     */
    val rtptime: Long
        get() = headTs - (startTs - latency)

    /** 32 位无符号 RTP timestamp（包头里实际写入的值）。 */
    val rtptime32: Int
        get() = (rtptime and 0xFFFFFFFFL).toInt()

    /** 推进时间轴（每个音频包发送后调用，frames=该包的每声道样本数）。 */
    fun advance(frames: Int) { headTs += frames }

    /** 当前 head_ts 对应的 NTP 时间（SyncPacket 的 last_sync 部分）。 */
    fun syncNtp(): Long = ts2ntp(headTs, sampleRate)

    companion object {
        /** 1900→1970 偏移秒数。 */
        const val NTP_EPOCH_OFFSET = 0x83AA7E80L

        /** 当前时间的 64 位 NTP 表示（对齐 pyatv timing.ntp_now）。 */
        fun ntpNow(): Long {
            val ms = System.currentTimeMillis()
            val seconds = ms / 1000
            val fracMicros = (ms % 1000) * 1000
            val secField = (seconds + NTP_EPOCH_OFFSET) and 0xFFFFFFFFL
            val fracField = (fracMicros shl 32) / 1_000_000L
            return (secField shl 32) or (fracField and 0xFFFFFFFFL)
        }

        /** NTP → (秒, 小数) 各 32 位。 */
        fun ntp2parts(ntp: Long): Pair<Long, Long> =
            Pair((ntp ushr 32) and 0xFFFFFFFFL, ntp and 0xFFFFFFFFL)

        /** (秒, 小数) → NTP 64 位。 */
        fun parts2ntp(sec: Long, frac: Long): Long =
            ((sec and 0xFFFFFFFFL) shl 32) or (frac and 0xFFFFFFFFL)

        /** NTP → RTP timestamp（对齐 pyatv timing.ntp2ts）。 */
        fun ntp2ts(ntp: Long, rate: Int): Long =
            (((ntp ushr 16) * rate) ushr 16)

        /**
         * RTP timestamp → NTP（对齐 pyatv timing.ts2ntp）。
         *
         * 关键修复（2026-06-06，HomePod 完全静音根因）：
         * 在真实 NTP 锚点下 headTs≈1.76e14，`timestamp shl 16`≈1.15e19，**超过 Long.MAX
         * (9.22e18) 变为负数**，后续【有符号】除法 `/rate` 截断方向错误，结果偏差约 66 年。
         * 于是 Sync 包的 last_sync NTP 与计时服务器的 ntpNow 严重不一致 → 设备算出的播放
         * 时刻差 66 年 → 全部音频包被判过期/未来而丢弃 → 完全静音（控制面却正常）。
         * pyatv 是 Python 无限精度整数，天然无此问题。Kotlin 必须用【无符号】除法：
         * `timestamp shl 16` 的 64 位位模式 < 2^64 是正确的，只要按无符号解释即可。
         */
        fun ts2ntp(timestamp: Long, rate: Int): Long =
            java.lang.Long.divideUnsigned(timestamp shl 16, rate.toLong()) shl 16
    }
}