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
 * 无限长 `audio/aac` HTTP/1.1 流：客户端 GET [path] 后回 200（无 Content-Length），
 * 随后把 [push] 进来的 ADTS 帧持续写给它。多客户端各自一个有界队列，[push] 扇出。
 * 队列满时丢最旧帧（实时音频宁可丢也不积压延迟）。
 */
class LiveAudioHttpServer {
    val path: String = "/live/" + java.util.UUID.randomUUID().toString().replace("-", "") + ".aac"
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val subscribers = ConcurrentHashMap<Socket, LinkedBlockingQueue<ByteArray>>()
    private val QUEUE_MAX = 256   // 约 256 帧 ≈ 6s@44.1k/1024spf，超出丢旧
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

    /** 推一帧编码后的 ADTS 数据，扇出给所有客户端。 */
    fun push(frame: ByteArray) {
        for ((_, q) in subscribers) {
            if (!q.offer(frame)) { q.poll(); q.offer(frame) }  // 丢最旧
        }
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
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            // 读完请求行 + 头（到空行）
            val reqLine = readLine(ins)
            val reqPath = reqLine.split(" ").getOrNull(1) ?: ""
            while (true) { val l = readLine(ins); if (l.isEmpty()) break }
            Log.i("LiveAudioHttp", "client GET $reqPath (match=${reqPath == path})")
            if (reqPath != path) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); return
            }
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                 "Content-Type: audio/aac\r\n" +
                 "transferMode.dlna.org: Streaming\r\n" +
                 "Connection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            connections++
            val q = LinkedBlockingQueue<ByteArray>(QUEUE_MAX)
            subscribers[sock] = q
            Log.i("LiveAudioHttp", "streaming started for $reqPath")
            try {
                while (running && !sock.isClosed) {
                    val frame = q.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    out.write(frame); out.flush()
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
