// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 无限长音频 HTTP/1.1 流：客户端 GET [path] 后回 200，随后把 [push] 进来的帧持续写给它。
 * 多客户端各自一个有界队列，[push] 扇出。队列满时丢最旧帧（实时音频宁可丢也不积压延迟）。
 *
 * Sonos 兼容性要点（行业实践，SWYH/swyh-rs/BubbleUPnP 同款）：
 *  - 正确响应 HEAD（Sonos 播放前会先 HEAD 嗅探，对 HEAD 灌流会被判 URI 非法）；
 *  - 绝不用 chunked（Sonos 对 chunked 音频流支持残缺），EOF 定界或假大 Content-Length；
 *  - [fakeContentLength]：WAV「无限长曲目」模式给假大长度（swyh-rs 的 u32::MAX 做法）；
 *    电台(AAC)模式不给长度（icecast 电台惯例）。
 *  - [streamHeader]：每个新订阅者先收到的固定头（WAV 模式 = 44 字节 RIFF 头）。
 */
class LiveAudioHttpServer(
    private val contentType: String = "audio/aac",
    pathExt: String = "aac",
    private val fakeContentLength: Long? = null,
    private val streamHeader: ByteArray? = null,
    /** 新观众 GET 订阅流时回调（视频直播用它触发编码器补关键帧，缩短首屏花屏窗口）。 */
    private val onSubscriber: () -> Unit = {},
    /** 每客户端队列容量。音频 256 帧≈6s；TS 视频包仅 188B，须给数千级（8192≈1.5MB≈1.2s@10Mbps）。 */
    private val queueMax: Int = 256,
) {
    val path: String = "/live/" + java.util.UUID.randomUUID().toString().replace("-", "") + ".$pathExt"
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val subscribers = ConcurrentHashMap<Socket, LinkedBlockingQueue<ByteArray>>()
    /** 累计被客户端成功 GET 拉流的次数（诊断用：判断 Sonos 是否真的来取流）。 */
    @Volatile var connections = 0; private set

    fun start(): Int {
        val s = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        server = s; running = true
        thread(name = "airsonic-live-http", isDaemon = true) {
            while (running) {
                val sock = try { s.accept() } catch (_: Throwable) { break }
                thread(isDaemon = true) { runCatching { serve(sock) } }
            }
        }
        return s.localPort
    }

    /**
     * 推一帧编码后的数据（AAC=完整 ADTS 帧 / WAV=PCM 块 / TS=188B 包），扇出给所有客户端。
     * 队列满丢最旧；返回 false=发生了丢弃（视频直播据此立刻补关键帧，把花屏窗口压到最短）。
     */
    fun push(frame: ByteArray): Boolean {
        var clean = true
        for ((_, q) in subscribers) {
            if (!q.offer(frame)) { q.poll(); q.offer(frame); clean = false }  // 丢最旧
        }
        return clean
    }

    fun stop() {
        running = false
        subscribers.keys.forEach { runCatching { it.close() } }
        subscribers.clear()
        runCatching { server?.close() }
        server = null
    }

    private fun serve(sock: Socket) {
        sock.use {
            if (!isLanClient(sock.inetAddress)) return   // 只服务局域网/回环来源，拒公网嗅探
            sock.soTimeout = 5000   // 请求阶段限时：防半开连接把 serve 线程挂死在 readLine
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            // 读完请求行 + 头（到空行）
            val reqLine = readLine(ins)
            val parts = reqLine.split(" ")
            val method = (parts.getOrNull(0) ?: "").uppercase()
            val reqPath = parts.getOrNull(1) ?: ""
            while (true) { val l = readLine(ins); if (l.isEmpty()) break }
            Log.i("LiveAudioHttp", "client $method $reqPath (match=${reqPath == path})")
            if (reqPath != path) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); return
            }
            val lenHeader = fakeContentLength?.let { "Content-Length: $it\r\n" } ?: ""
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                 "Content-Type: $contentType\r\n" +
                 lenHeader +
                 "icy-name: AirSonic\r\n" +
                 "Accept-Ranges: none\r\n" +
                 "transferMode.dlna.org: Streaming\r\n" +
                 "Connection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            if (method == "HEAD") { Log.i("LiveAudioHttp", "HEAD answered (no body)"); return }
            sock.soTimeout = 0      // 流阶段不再限读（我们只写不读）
            connections++
            runCatching { onSubscriber() }
            streamHeader?.let { out.write(it); out.flush() }
            val q = LinkedBlockingQueue<ByteArray>(queueMax)
            subscribers[sock] = q
            Log.i("LiveAudioHttp", "streaming started for $reqPath")
            try {
                while (running && !sock.isClosed) {
                    val frame = q.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    // 批量取走积压帧一次写盘（188B TS 包逐包 flush 抖动大、吞吐低）
                    var batch = frame
                    var n = 0
                    while (n < 127) {
                        val next = q.poll() ?: break
                        batch += next; n++
                    }
                    out.write(batch); out.flush()
                }
            } finally {
                subscribers.remove(sock)
                Log.i("LiveAudioHttp", "streaming ended for $reqPath (subscriber removed)")
            }
        }
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
}
