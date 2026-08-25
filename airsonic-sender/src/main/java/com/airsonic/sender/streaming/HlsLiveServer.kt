// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * HLS 直播服务器（EVENT 型滑动窗口播放列表 + 内存 TS 分片 + LL-HLS 小片）。
 *
 * 为什么需要它：AirPlay 接收端（macOS/tvOS 的 AVPlayer）经 play-queue 播「无限长裸 TS」
 * 永远停在 loading（ffmpeg 产流 + 假大 Content-Length 两种形态实测都如此），
 * 而 HLS 是 AVPlayer 的原生直播形态，实测秒开。DLNA 渲染器继续走 LiveAudioHttpServer 裸 TS。
 *
 * LL-HLS（低延迟扩展，RFC 8216 bis / Apple LL-HLS）：
 *  - 每个分片再按墙钟切成 ~[partTargetMs]ms 的 partial segment（EXT-X-PART），
 *    AVPlayer 不用等整个分片关闭即可逐小片下载——直播缓冲量从 ~3 个分片降到 ~3 个小片；
 *  - EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES：客户端用 ?_HLS_msn=M&_HLS_part=N 长轮询，
 *    服务器阻塞到新小片出现立即返回，省掉固定轮询间隔；
 *  - EXT-X-PRELOAD-HINT：指向正在写入的小片，GET 它阻塞到该小片关闭后直出。
 * 经典 HLS 客户端忽略这些标签，向后兼容（分片整段 URI 始终可拉）。
 *
 * 用法：
 *  - [acceptPacket]：TsMuxer 的 188B 包源源不断灌入（内部追加到当前小片/分片缓冲）；
 *  - [boundary]：编码器在每个关键帧前调用 → 关闭当前小片与分片并发布（时长=相邻关键帧 pts 差）。
 *    每个分片因此天然以「PAT/SDT/PMT → SPS/PPS → IDR」起手（切片点配合 TsMuxer.forcePatPmt）。
 *  - 播放列表只含已关闭分片（滑动窗口 [windowSize] 个）+ 当前开放分片的已关闭小片。
 */
class HlsLiveServer(
    /** 播放列表保留的已关闭分片数。0.5s GOP → 窗口≈3s。 */
    private val windowSize: Int = 6,
    /** 分片最短时长（同步帧导致的超短 GOP 不单独成片，并进当前分片）。 */
    private val minSegmentUs: Long = 800_000,
    /** 小片目标时长（墙钟毫秒）。LL-HLS 的延迟量级 ≈ 3 × 该值（PART-HOLD-BACK）。 */
    private val partTargetMs: Long = 250,
    /** false=经典 HLS（无 LL 标签），用于 A/B 对照与兜底。 */
    private val lowLatency: Boolean = true,
    /** false=只发 LL 头部（SERVER-CONTROL/PART-INF），不发 PART/PRELOAD-HINT（A/B 分解用）。 */
    private val llParts: Boolean = true,
    private val onLog: (String) -> Unit = {},
    /** URL 基路径（默认随机；测试可传固定值便于 curl 解剖）。 */
    baseId: String = java.util.UUID.randomUUID().toString().replace("-", ""),
) {
    private data class Part(val idx: Int, val data: ByteArray, val durationUs: Long)
    private data class Segment(
        val seq: Long, val data: ByteArray, val durationUs: Long,
        val wallStartMs: Long, val parts: List<Part>,
    )

    private val base = "/hls/$baseId"
    /** 播放列表路径（GET 该路径拿 m3u8）。分片路径 = $base/seg<seq>.ts，小片 = $base/seg<seq>.part<i>.ts。 */
    val playlistPath = "$base/live.m3u8"

    private val lock = Object()
    private val published = ArrayDeque<Segment>()
    // ---- 当前开放分片 ----
    private var curBuf = ByteArrayOutputStream()          // 整段（含所有小片）
    private var curStartPtsUs = -1L
    private var curSeq = 0L
    private val curParts = ArrayList<Part>()              // 开放分片里已关闭的小片
    private var curPartBuf = ByteArrayOutputStream()      // 正在写入的小片
    private var curPartStartMs = -1L
    private var nextSeq = 0L
    /** 已关闭（可播）分片数。CastEngine 等够若干片再发 play，避免 AVPlayer 起手窗口太空。 */
    @Volatile var closedSegments = 0; private set
    /** 播放列表被拉取次数（诊断：确认 AVPlayer 真的在轮询）。 */
    @Volatile var playlistHits = 0; private set
    /** LL-HLS 阻塞刷新次数（诊断：>0 说明接收端真的进入了低延迟模式）。 */
    @Volatile var blockingHits = 0; private set

    private var server: ServerSocket? = null
    @Volatile private var running = false

    /** 测试钩子：非空时播放列表/分片绕过内存窗口，直接从该目录按文件名服务（逐字节对照实验）。 */
    @Volatile var verbatimDir: java.io.File? = null

    fun start(): Int {
        val s = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        server = s; running = true
        thread(name = "airsonic-hls-http", isDaemon = true) {
            while (running) {
                val sock = try { s.accept() } catch (_: Throwable) { break }
                thread(isDaemon = true) { runCatching { serve(sock) } }
            }
        }
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        synchronized(lock) {
            published.clear(); curBuf.reset(); curPartBuf.reset(); curParts.clear()
            lock.notifyAll()
        }
    }

    /** 直接发布一个完整分片（harness 对照实验用：灌外部 TS 文件验证服务器/播放列表行为）。 */
    fun publishSegment(data: ByteArray, durationUs: Long, wallStartMs: Long? = null) {
        synchronized(lock) {
            val ws = wallStartMs ?: (System.currentTimeMillis() - durationUs / 1000)
            published.addLast(Segment(nextSeq++, data, durationUs, ws, emptyList()))
            closedSegments++
            while (published.size > windowSize) published.removeFirst()
            lock.notifyAll()
        }
    }

    /** 灌一个 TS 包（追加到当前小片与整段缓冲；小片按墙钟到点自动关闭）。 */
    fun acceptPacket(pkt: ByteArray) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (curPartStartMs < 0) curPartStartMs = now
            curBuf.write(pkt); curPartBuf.write(pkt)
            if (now - curPartStartMs >= partTargetMs) closePartLocked(now)
        }
    }

    /** 关闭当前小片（开放分片内序号递增），唤醒阻塞中的 LL-HLS 请求。 */
    private fun closePartLocked(nowMs: Long) {
        val data = curPartBuf.toByteArray()
        curPartBuf.reset()
        if (data.isNotEmpty()) {
            val durUs = (nowMs - curPartStartMs) * 1000
            curParts.add(Part(curParts.size, data, durUs))
            lock.notifyAll()
        }
        curPartStartMs = nowMs
    }

    /**
     * 关键帧边界（ptsUs 与该帧的归零 pts 同基）：关闭当前小片与分片并发布，开新分片。
     * 距上个边界不足 [minSegmentUs] 时忽略（防同步帧切出超短分片撑爆 TARGETDURATION）。
     */
    fun boundary(ptsUs: Long) {
        synchronized(lock) {
            if (curStartPtsUs < 0) { curStartPtsUs = ptsUs; curSeq = nextSeq++; return }   // 首个关键帧：开第 0 片
            val dur = ptsUs - curStartPtsUs
            if (dur < minSegmentUs) return
            closePartLocked(System.currentTimeMillis())
            val data = curBuf.toByteArray()
            curBuf.reset()
            if (data.isNotEmpty()) {
                val now = System.currentTimeMillis()
                published.addLast(Segment(curSeq, data, dur, now - dur / 1000, curParts.toList()))
                closedSegments++
                while (published.size > windowSize) published.removeFirst()
                onLog("HLS 分片#${curSeq} 发布 ${data.size}B ${dur / 1000}ms 小片${curParts.size} (窗口 ${published.size})")
            }
            curParts.clear()
            curStartPtsUs = ptsUs
            curSeq = nextSeq++
            lock.notifyAll()
        }
    }

    // ---- HTTP ----
    private fun serve(sock: Socket) {
        sock.use {
            if (!isLanClient(sock.inetAddress)) return
            sock.soTimeout = 8000
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            val reqLine = readLine(ins)
            val parts0 = reqLine.split(" ")
            val method = (parts0.getOrNull(0) ?: "").uppercase()
            val fullPath = parts0.getOrNull(1) ?: ""
            val reqPath = fullPath.substringBefore('?')
            val query = fullPath.substringAfter('?', "")
            val hdrs = StringBuilder(reqLine)
            while (true) { val l = readLine(ins); if (l.isEmpty()) break; hdrs.append(" | ").append(l) }
            onLog("REQ $hdrs")
            // 逐字节对照模式：直接服务目录里的 ffmpeg 原文件
            verbatimDir?.let { dir ->
                val name = reqPath.substringAfterLast('/')
                val f = java.io.File(dir, name)
                if (f.isFile && (name.endsWith(".m3u8") || name.endsWith(".ts"))) {
                    val ct = if (name.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
                    respond(out, 200, "OK", ct, f.readBytes(), method == "HEAD", lastModifiedMs = f.lastModified())
                } else respond(out, 404, "Not Found", "text/plain", null, method == "HEAD")
                return
            }
            when {
                reqPath == playlistPath -> {
                    // LL-HLS 阻塞刷新：客户端报它已有的 msn/part，服务器等到有更新再返回
                    val msn = Regex("(?:^|&)_HLS_msn=(\\d+)").find(query)?.groupValues?.get(1)?.toLongOrNull()
                    val part = Regex("(?:^|&)_HLS_part=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull()
                    if (msn != null) { blockingHits++; awaitUpdate(msn, part) }
                    val body = buildPlaylist().toByteArray(Charsets.US_ASCII)
                    playlistHits++
                    respond(out, 200, "OK", "application/vnd.apple.mpegurl", body, method == "HEAD",
                        lastModifiedMs = System.currentTimeMillis())
                }
                reqPath.startsWith("$base/seg") && reqPath.endsWith(".ts") -> {
                    val name = reqPath.removePrefix("$base/seg").removeSuffix(".ts")
                    if (name.contains(".part")) {
                        // 小片：已关闭的直出；PRELOAD-HINT 指向的未关闭小片阻塞等关闭
                        val seq = name.substringBefore(".part").toLongOrNull()
                        val idx = name.substringAfter(".part").toIntOrNull()
                        servePart(out, seq, idx, method == "HEAD")
                    } else {
                        val seq = name.toLongOrNull()
                        val seg = synchronized(lock) { published.firstOrNull { it.seq == seq } }
                        onLog("GET seg seq=$seq hit=${seg != null} (窗口 ${published.firstOrNull()?.seq}..${published.lastOrNull()?.seq})")
                        if (seg == null) respond(out, 404, "Not Found", "text/plain", null, method == "HEAD")
                        else respond(out, 200, "OK", "video/mp2t", seg.data, method == "HEAD",
                            lastModifiedMs = seg.wallStartMs)
                    }
                }
                else -> respond(out, 404, "Not Found", "text/plain", null, method == "HEAD")
            }
        }
    }

    /** 阻塞式播放列表刷新：等到「存在 seq>=msn 且小片数>part 的分片」或 seq>msn，超时兜底返回当前列表。 */
    private fun awaitUpdate(msn: Long, part: Int?) {
        val deadline = System.currentTimeMillis() + 3000
        synchronized(lock) {
            while (running) {
                val last = published.lastOrNull()
                val satisfied = when {
                    last == null -> false
                    last.seq > msn -> true                       // 想要的分片已完整发布（甚至更新）
                    part == null -> last.seq >= msn              // 只要整片：已有
                    else -> partsOfLocked(msn).size > part       // 要小片 N+1 已关闭
                }
                if (satisfied) return
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) return
                runCatching { lock.wait(wait) }
            }
        }
    }

    /** seq 分片当前可用的小片（已发布的取快照；开放分片取已关闭小片）。 */
    private fun partsOfLocked(seq: Long): List<Part> {
        published.firstOrNull { it.seq == seq }?.let { return it.parts }
        return if (curSeq == seq) curParts else emptyList()
    }

    private fun servePart(out: java.io.OutputStream, seq: Long?, idx: Int?, headOnly: Boolean) {
        if (seq == null || idx == null) {
            respond(out, 404, "Not Found", "text/plain", null, headOnly); return
        }
        val deadline = System.currentTimeMillis() + 3000
        synchronized(lock) {
            while (running) {
                // 已发布分片的小片 / 开放分片的已关闭小片
                published.firstOrNull { it.seq == seq }?.parts?.firstOrNull { it.idx == idx }?.let { p ->
                    respond(out, 200, "OK", "video/mp2t", p.data, headOnly); return
                }
                if (curSeq == seq) {
                    curParts.firstOrNull { it.idx == idx }?.let { p ->
                        respond(out, 200, "OK", "video/mp2t", p.data, headOnly); return
                    }
                    // 还未关闭（PRELOAD-HINT）：阻塞等它关闭
                    val wait = deadline - System.currentTimeMillis()
                    if (wait <= 0) break
                    runCatching { lock.wait(wait) }
                    continue
                }
                break
            }
        }
        respond(out, 404, "Not Found", "text/plain", null, headOnly)
    }

    private fun buildPlaylist(): String {
        val segs: List<Segment>
        val openSeq: Long
        val openParts: List<Part>
        val openPartIdx: Int
        synchronized(lock) {
            segs = published.toList()
            openSeq = curSeq
            openParts = curParts.toList()
            openPartIdx = curParts.size
        }
        val target = maxOf(2, ((segs.maxOfOrNull { it.durationUs } ?: 1_000_000L) + 999_999) / 1_000_000)
        val partTargetSec = "%.3f".format(partTargetMs / 1000.0)
        val holdBackSec = "%.3f".format(partTargetMs * 3 / 1000.0)
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:${if (lowLatency) 9 else 3}\n")
        sb.append("#EXT-X-TARGETDURATION:$target\n")
        // LL-HLS：阻塞刷新 + 小片保持量（≈3 个小片，决定 AVPlayer 的直播延迟量级）
        if (lowLatency) {
            sb.append("#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=$holdBackSec\n")
            sb.append("#EXT-X-PART-INF:PART-TARGET=$partTargetSec\n")
        }
        sb.append("#EXT-X-MEDIA-SEQUENCE:${segs.firstOrNull()?.seq ?: 0}\n")
        // PROGRAM-DATE-TIME：对齐 ffmpeg 产出形态（EXTINF 之后、URI 之前）。
        // CoreMedia HLS 子流靠它把分片映射上时间轴。
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
        for (s in segs) {
            // 小片先于所属分片的 EXTINF（spec 顺序）；part0 含 IDR 标 INDEPENDENT
            if (lowLatency && llParts) for (p in s.parts) {
                sb.append("#EXT-X-PART:DURATION=${"%.6f".format(p.durationUs / 1_000_000.0)},")
                sb.append("URI=\"seg${s.seq}.part${p.idx}.ts\"")
                if (p.idx == 0) sb.append(",INDEPENDENT=YES")
                sb.append("\n")
            }
            sb.append("#EXTINF:${"%.6f".format(s.durationUs / 1_000_000.0)},\n")
            sb.append("#EXT-X-PROGRAM-DATE-TIME:${fmt.format(java.util.Date(s.wallStartMs))}\n")
            sb.append("seg${s.seq}.ts\n")
        }
        // 开放分片的已关闭小片 + 预载提示（GET 该 URI 阻塞到小片关闭）
        if (lowLatency && llParts) {
            for (p in openParts) {
                sb.append("#EXT-X-PART:DURATION=${"%.6f".format(p.durationUs / 1_000_000.0)},")
                sb.append("URI=\"seg${openSeq}.part${p.idx}.ts\"")
                if (p.idx == 0) sb.append(",INDEPENDENT=YES")
                sb.append("\n")
            }
            sb.append("#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"seg${openSeq}.part${openPartIdx}.ts\"\n")
        }
        return sb.toString()
    }

    private fun respond(out: java.io.OutputStream, code: Int, msg: String, contentType: String,
                        body: ByteArray?, headOnly: Boolean, lastModifiedMs: Long? = null) {
        // 与实测可播的 python SimpleHTTPRequestHandler 响应头完全对齐（含 Server/Date/Last-Modified）：
        // CoreMedia HLS 客户端对响应头形态敏感，缺 Date 时永远 loading（实测）。
        val date = httpDateFormat.format(java.util.Date())
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\n")
        sb.append("Server: AirSonic/1.0\r\n")
        sb.append("Date: $date\r\n")
        sb.append("Content-Type: $contentType\r\n")
        if (lastModifiedMs != null) sb.append("Last-Modified: ${httpDateFormat.format(java.util.Date(lastModifiedMs))}\r\n")
        sb.append("Content-Length: ${body?.size ?: 0}\r\n")
        sb.append("Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null && !headOnly) out.write(body)
        out.flush()
    }

    private fun readLine(ins: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    companion object {
        /** RFC 1123 GMT 格式（HTTP Date 头）。SimpleDateFormat 非线程安全，每响应新建代价可忽略。 */
        private val httpDateFormat: java.text.SimpleDateFormat
            get() = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
    }
}
