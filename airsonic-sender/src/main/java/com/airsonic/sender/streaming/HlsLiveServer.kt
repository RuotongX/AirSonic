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
 * HLS 直播服务器（EVENT 型滑动窗口播放列表 + 内存 TS 分片）。
 *
 * 为什么需要它：AirPlay 接收端（macOS/tvOS 的 AVPlayer）经 play-queue 播「无限长裸 TS」
 * 永远停在 loading（ffmpeg 产流 + 假大 Content-Length 两种形态实测都如此），
 * 而 HLS 是 AVPlayer 的原生直播形态，实测秒开。DLNA 渲染器继续走 LiveAudioHttpServer 裸 TS。
 *
 * 用法：
 *  - [acceptPacket]：TsMuxer 的 188B 包源源不断灌入（内部追加到当前分片缓冲）；
 *  - [boundary]：编码器在每个关键帧前调用 → 关闭当前分片并发布（时长=相邻关键帧 pts 差）。
 *    每个分片因此天然以「PAT/SDT/PMT → SPS/PPS → IDR」起手（切片点配合 TsMuxer.forcePatPmt）。
 *  - 播放列表只含已关闭分片（滑动窗口 [windowSize] 个）；AVPlayer 周期轮询 m3u8 追新分片。
 */
class HlsLiveServer(
    /** 播放列表保留的已关闭分片数。1s GOP → 窗口≈6s，起步延迟≈2-3 个分片。 */
    private val windowSize: Int = 6,
    /** 分片最短时长（同步帧导致的超短 GOP 不单独成片，并进当前分片）。 */
    private val minSegmentUs: Long = 800_000,
    private val onLog: (String) -> Unit = {},
    /** URL 基路径（默认随机；测试可传固定值便于 curl 解剖）。 */
    baseId: String = java.util.UUID.randomUUID().toString().replace("-", ""),
) {
    private data class Segment(val seq: Long, val data: ByteArray, val durationUs: Long, val wallStartMs: Long)

    private val base = "/hls/$baseId"
    /** 播放列表路径（GET 该路径拿 m3u8）。分片路径 = $base/seg<seq>.ts。 */
    val playlistPath = "$base/live.m3u8"

    private val lock = Object()
    private val published = ArrayDeque<Segment>()
    private var curBuf = ByteArrayOutputStream()
    private var curStartPtsUs = -1L
    private var nextSeq = 0L
    /** 已关闭（可播）分片数。CastEngine 等够 3 片再发 play，避免 AVPlayer 起手窗口太空。 */
    @Volatile var closedSegments = 0; private set
    /** 播放列表被拉取次数（诊断：确认 AVPlayer 真的在轮询）。 */
    @Volatile var playlistHits = 0; private set

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
        synchronized(lock) { published.clear(); curBuf.reset() }
    }

    /** 直接发布一个完整分片（harness 对照实验用：灌外部 TS 文件验证服务器/播放列表行为）。 */
    fun publishSegment(data: ByteArray, durationUs: Long, wallStartMs: Long? = null) {
        synchronized(lock) {
            val ws = wallStartMs ?: (System.currentTimeMillis() - durationUs / 1000)
            published.addLast(Segment(nextSeq++, data, durationUs, ws))
            closedSegments++
            while (published.size > windowSize) published.removeFirst()
        }
    }

    /** 灌一个 TS 包（追加到当前未关闭分片）。 */
    fun acceptPacket(pkt: ByteArray) {
        synchronized(lock) { curBuf.write(pkt) }
    }

    /**
     * 关键帧边界（ptsUs 与该帧的归零 pts 同基）：关闭当前分片并发布，开新分片。
     * 距上个边界不足 [minSegmentUs] 时忽略（防同步帧切出超短分片撑爆 TARGETDURATION）。
     */
    fun boundary(ptsUs: Long) {
        synchronized(lock) {
            if (curStartPtsUs < 0) { curStartPtsUs = ptsUs; return }   // 首个关键帧：开第 0 片
            val dur = ptsUs - curStartPtsUs
            if (dur < minSegmentUs) return
            val data = curBuf.toByteArray()
            curBuf.reset()
            if (data.isNotEmpty()) {
                val now = System.currentTimeMillis()
                published.addLast(Segment(nextSeq++, data, dur, now - dur / 1000))
                closedSegments++
                while (published.size > windowSize) published.removeFirst()
                onLog("HLS 分片#${nextSeq - 1} 发布 ${data.size}B ${dur / 1000}ms (窗口 ${published.size})")
            }
            curStartPtsUs = ptsUs
        }
    }

    // ---- HTTP ----
    private fun serve(sock: Socket) {
        sock.use {
            if (!isLanClient(sock.inetAddress)) return
            sock.soTimeout = 5000
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            val reqLine = readLine(ins)
            val parts = reqLine.split(" ")
            val method = (parts.getOrNull(0) ?: "").uppercase()
            val reqPath = parts.getOrNull(1) ?: ""
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
                    val body = buildPlaylist().toByteArray(Charsets.US_ASCII)
                    playlistHits++
                    respond(out, 200, "OK", "application/vnd.apple.mpegurl", body, method == "HEAD",
                        lastModifiedMs = System.currentTimeMillis())
                }
                reqPath.startsWith("$base/seg") && reqPath.endsWith(".ts") -> {
                    val seq = reqPath.removePrefix("$base/seg").removeSuffix(".ts").toLongOrNull()
                    val seg = synchronized(lock) { published.firstOrNull { it.seq == seq } }
                    onLog("GET seg seq=$seq hit=${seg != null} (窗口 ${published.firstOrNull()?.seq}..${published.lastOrNull()?.seq})")
                    if (seg == null) respond(out, 404, "Not Found", "text/plain", null, method == "HEAD")
                    else respond(out, 200, "OK", "video/mp2t", seg.data, method == "HEAD",
                        lastModifiedMs = seg.wallStartMs)
                }
                else -> respond(out, 404, "Not Found", "text/plain", null, method == "HEAD")
            }
        }
    }

    private fun buildPlaylist(): String {
        val segs = synchronized(lock) { published.toList() }
        val target = maxOf(2, ((segs.maxOfOrNull { it.durationUs } ?: 1_000_000L) + 999_999) / 1_000_000)
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:$target\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:${segs.firstOrNull()?.seq ?: 0}\n")
        // PROGRAM-DATE-TIME：对齐 ffmpeg 产出形态（EXTINF 之后、URI 之前）。
        // CoreMedia HLS 子流靠它把分片映射上时间轴。
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
        for (s in segs) {
            sb.append("#EXTINF:${"%.6f".format(s.durationUs / 1_000_000.0)},\n")
            sb.append("#EXT-X-PROGRAM-DATE-TIME:${fmt.format(java.util.Date(s.wallStartMs))}\n")
            sb.append("seg${s.seq}.ts\n")
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
