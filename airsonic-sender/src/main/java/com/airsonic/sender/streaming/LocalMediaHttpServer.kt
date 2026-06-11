// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 单资源 HTTP/1.1 服务：把一个 [RangeSource] 以 Range/206 流式输出给 AirPlay TV 拉流。
 * 仅服务随机 token 化路径 [path]；start() 返回端口，stop() 关闭。
 */
class LocalMediaHttpServer(private val source: RangeSource) {
    val path: String = "/v/" + java.util.UUID.randomUUID().toString().replace("-", "")
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(): Int {
        val s = ServerSocket(0, 4, InetAddress.getByName("0.0.0.0"))
        server = s; running = true
        thread(name = "airsonic-http", isDaemon = true) {
            while (running) {
                val sock = try { s.accept() } catch (_: Throwable) { break }
                thread(isDaemon = true) { runCatching { handle(sock) } }
            }
        }
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(sock: Socket) {
        sock.use {
            if (!isLanClient(sock.inetAddress)) return   // 只服务局域网/回环来源，拒公网嗅探
            sock.soTimeout = 15000   // 防 slowloris/半开连接永久占住 handler 线程 + ContentResolver fd
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            val reader = ins.bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val reqPath = parts.getOrNull(1) ?: ""
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(":").trim()
            }
            if (reqPath != path) { writeStatus(out, 404, "Not Found"); return }
            val total = source.length
            val range = HttpRange.parse(rangeHeader, total)
            if (range == null) { writeStatus(out, 416, "Range Not Satisfiable"); return }
            val (start, end) = range
            val len = end - start + 1
            val partial = rangeHeader != null
            val sb = StringBuilder()
            sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: ${source.mimeType}\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("transferMode.dlna.org: Streaming\r\n")
            sb.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
            sb.append("Content-Length: $len\r\n")
            if (partial) sb.append("Content-Range: bytes $start-$end/$total\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (method == "HEAD") { out.flush(); return }
            source.open(start).use { src ->
                val buf = ByteArray(64 * 1024)
                var remaining = len
                while (remaining > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            out.flush()
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }
}