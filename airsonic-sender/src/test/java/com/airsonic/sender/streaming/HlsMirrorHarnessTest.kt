// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import com.airsonic.sender.pairing.PairingHandshake
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

/**
 * HLS 镜像链路 harness：JVM 上用「TsMuxer + HlsLiveServer」复现手机端切片管线（sample.h264/aac
 * 实时循环喂流），经 play-queue 投到 macOS 接收端，验证 AVPlayer 是否真播我们的 HLS。
 * 仅当 /tmp/jvm_harness_go 存在时运行；ATV_HOST 指定接收端；ATV_HOLD_SECONDS 控制观察时长。
 */
class HlsMirrorHarnessTest {
    private val HOST = System.getenv("ATV_HOST") ?: "172.23.25.241"
    private val PORT = 7000

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

    @Test fun run() {
        if (!File("/tmp/jvm_harness_go").exists()) { println("HARNESS skipped (no go marker)"); return }
        val h264File = File("/tmp/airsonic-http/sample.h264")
        val aacFile = File("/tmp/airsonic-http/sample.aac")
        if (!h264File.exists()) { println("skip: no sample.h264"); return }

        // ---- 聚帧（与 TsMuxerRealFileTest 同款）----
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
        println("=== HLS HARNESS AUs=${aus.size} keys=${aus.count { it.key }} aac=${aacFrames.size} ===")

        // ---- 切片管线：TsMuxer → HlsLiveServer（关键帧前 forcePatPmt + boundary，与 caster 一致）----
        // HLS_FEED=files：对照实验——不产自己的流，把 ffmpeg 的 hls/live*.ts 灌进同一个服务器，
        // 用于区分「服务器/播放列表行为」与「TS 字节」哪一侧不被 CoreMedia 接受。
        // HLS_CONTAINER=fmp4：fMP4 容器版 LL-HLS（小片=独立 fragment，AVPlayer 唯一接受的小片形态）。
        val fmp4Mode = System.getenv("HLS_CONTAINER") == "fmp4"
        val hls = HlsLiveServer(onLog = { println(">>> HLS $it") },
            lowLatency = System.getenv("HLS_LL") != "off",
            llParts = System.getenv("HLS_LL_PARTS") != "off",
            container = if (fmp4Mode) Container.FMP4 else Container.TS,
            withAudio = aacFrames.isNotEmpty(),
            baseId = if (System.getenv("HLS_FEED") == "verbatim") "debug" else
                java.util.UUID.randomUUID().toString().replace("-", ""))
        val port = hls.start()
        val feedT0 = System.currentTimeMillis()
        fun lagTag() = "[产${"%.1f".format((System.currentTimeMillis() - feedT0) / 1000.0)}s]"
        val feeding = java.util.concurrent.atomic.AtomicBoolean(true)
        if (System.getenv("HLS_FEED") == "verbatim") {
            // 播放列表和分片都直接服务 ffmpeg 目录原文件（与 python 成功的服务逐字节一致）
            hls.verbatimDir = File("/tmp/airsonic-http/hls")
            println("=== HLS HARNESS feed=verbatim ===")
        } else if (System.getenv("HLS_FEED") == "files") {
            val dir = File("/tmp/airsonic-http/hls")
            fun seqOf(f: File) = f.name.removePrefix("live").removeSuffix(".ts").toLong()
            fun allSegs() = dir.listFiles { f -> f.name.matches(Regex("live\\d+\\.ts")) }.orEmpty().sortedBy { seqOf(it) }
            // 只发布「已有后继文件」的分片：ffmpeg 正在写的最新片读出来是截断的（喂给 AVPlayer 必报 no samples）
            fun completeSegs(publishedNames: Set<String>): List<File> {
                val all = allSegs().filter { it.name !in publishedNames }
                return all.dropLast(1)   // 最新一片可能在写，丢弃
            }
            val names = HashSet<String>()
            val t0 = System.currentTimeMillis()
            completeSegs(names).takeLast(6).forEachIndexed { i, f ->
                hls.publishSegment(f.readBytes(), 2_000_000, wallStartMs = t0 - (6 - i) * 2000)
                names += f.name
            }
            println("=== HLS HARNESS feed=files 起手 $names ===")
            // ffmpeg 还在产新分片：跟着发布（仍跳过最新片），保持播放列表增长
            thread(isDaemon = true, name = "hls-file-feeder") {
                while (feeding.get()) {
                    Thread.sleep(500)
                    completeSegs(names).forEach { f ->
                        hls.publishSegment(f.readBytes(), 2_000_000); names += f.name
                        println(">>> HLS 发布文件分片 ${f.name}")
                    }
                }
            }
        } else if (fmp4Mode) {
        // fMP4：原始 Annex-B 帧直送服务器（与 caster 的 onRawVideoFrame 旁路同形），不经过 TsMuxer
        hls.setVideoConfig(sps!!, pps!!)
        val frameDurUs = 33_333L; val aacDurUs = 23_220L
        val loopDurUs = aus.size * frameDurUs
        val ptsOffset = 1_000_000L   // 与 TS 路径一致：+1s 惯例（CoreMedia 对 0 起播有拒产样本风险）
        println("=== HLS HARNESS container=fmp4 ===")
        thread(isDaemon = true, name = "hls-fmp4-feeder") {
            var loop = 0L
            while (feeding.get()) {
                var ai = 0
                aus.forEachIndexed { i, au ->
                    if (!feeding.get()) return@thread
                    val pts = ptsOffset + loop * loopDurUs + i * frameDurUs
                    if (au.key) hls.boundary(pts)
                    hls.acceptVideoFrame(au.data, pts, au.key)
                    while (ai < aacFrames.size && ai * aacDurUs <= i * frameDurUs) {
                        hls.acceptAudioFrame(aacFrames[ai], ptsOffset + loop * loopDurUs + ai * aacDurUs); ai++
                    }
                    Thread.sleep(33)
                }
                loop++
            }
        }
        } else {
        val muxer = TsMuxer(audioPid = if (aacFrames.isNotEmpty()) 0x102 else null) { pkt -> hls.acceptPacket(pkt) }
        muxer.setSpsPps(sps!!, pps!!)
        val frameDurUs = 33_333L; val aacDurUs = 23_220L
        val loopDurUs = aus.size * frameDurUs
        // 时间戳不从 0 起：ffmpeg 首 PCR≈0.7s；验证 CoreMedia HLS 子流是否因 0 起播拒绝产样
        val ptsOffset = 1_000_000L
        thread(isDaemon = true, name = "hls-feeder") {
            var loop = 0L
            while (feeding.get()) {
                var ai = 0
                aus.forEachIndexed { i, au ->
                    if (!feeding.get()) return@thread
                    val pts = ptsOffset + loop * loopDurUs + i * frameDurUs
                    if (au.key) { muxer.forcePatPmt(); hls.boundary(pts) }
                    muxer.writeVideoFrame(au.data, pts, au.key)
                    while (ai < aacFrames.size && ai * aacDurUs <= i * frameDurUs) {
                        muxer.writeAudioFrame(aacFrames[ai], ptsOffset + loop * loopDurUs + ai * aacDurUs); ai++
                    }
                    Thread.sleep(33)
                }
                loop++
            }
        }
        }

        // ---- 配对（持久身份，Mac 已配对）----
        val seedFile = File("/tmp/jvm_ltseed"); val pidFile = File("/tmp/jvm_pid")
        val ltSeed = if (seedFile.exists()) seedFile.readBytes()
            else ByteArray(32).also { SecureRandom().nextBytes(it); seedFile.writeBytes(it) }
        val pid = if (pidFile.exists()) pidFile.readText().trim()
            else UUID.randomUUID().toString().also { pidFile.writeText(it) }
        val hs = PairingHandshake(HOST, PORT, pid, ltSeed)
        val ok = hs.pairVerify { println("verify: $it") }
        println(">>> pairVerify = $ok")
        if (!ok) { feeding.set(false); hls.stop(); return }

        // ---- 等 3 片 → play → 观察事件（verbatim 模式内容在磁盘上，无需等切片）----
        var sw = 0
        while (hls.closedSegments < 3 && sw < 100 && hls.verbatimDir == null) { Thread.sleep(100); sw++ }
        println(">>> 起手分片=${hls.closedSegments} url=http://$HOST:$port${hls.playlistPath}")
        val ctl = AirplayVideoController(HOST, hs)
        ctl.onEvent = { _, body ->
            runCatching {
                val outer = BPlist.decode(body) as? Map<*, *>
                val data = (outer?.get("params") as? Map<*, *>)?.get("data") as? ByteArray
                val inner = data?.let { BPlist.decode(it) } as? Map<*, *> ?: return@runCatching
                val name = inner["name"]
                // 全量事件打印（readyToPlay/playing 是判据）；状态快照只摘关键字段防刷屏
                if (inner["type"] == "playbackState" && inner["params"] != null) {
                    val p = inner["params"] as? Map<*, *>
                    println(">>> EVENT-DECODED: ${lagTag()} playbackState name=$name state=${p?.get("playbackState")} rate=${p?.get("rate")} pos=${p?.get("position")}")
                } else println(">>> EVENT-DECODED: ${lagTag()} $inner")
            }
        }
        val conn = ctl.connect()
        println(">>> connect = $conn")
        if (conn) {
            val playOk = ctl.play("http://$HOST:$port${hls.playlistPath}", 0.0)
            println(">>> play = $playOk status=${ctl.lastStatus}")
            val holdMs = (System.getenv("ATV_HOLD_SECONDS") ?: "40").toLong() * 1000
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < holdMs) {
                Thread.sleep(5000)
                println(">>> 运行中 片=${hls.closedSegments} 单x${hls.playlistHits}")
            }
        }
        feeding.set(false)
        hls.stop()
    }
}
