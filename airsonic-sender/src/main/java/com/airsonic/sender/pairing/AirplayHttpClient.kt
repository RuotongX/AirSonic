// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.pairing

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 极简的 AirPlay HTTP/RTSP 风格请求客户端（裸 Socket）。
 *
 * AirPlay 配对通过 POST 到 `/pair-setup` 与 `/pair-verify`，
 * body 为 TLV8 二进制，Content-Type 为 `application/octet-stream`。
 *
 * ⚠️ 真机（HomePod/AppleTV）的 transient pair-setup 把会话状态绑定在 **同一 TCP 连接**上：
 *    M1 与 M3 必须走同一条连接，否则 M3 会返回 470 Connection Authorization Required。
 *    因此本客户端默认**复用持久连接**（keep-alive），仅在 [close] 时断开。
 */
class AirplayHttpClient(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 5000
) {
    data class Response(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null

    private fun ensureConnected() {
        val s = socket
        if (s != null && s.isConnected && !s.isClosed) return
        close()
        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(host, port), timeoutMs)
        newSocket.soTimeout = timeoutMs
        newSocket.tcpNoDelay = true
        socket = newSocket
        input = BufferedInputStream(newSocket.getInputStream())
        output = newSocket.getOutputStream()
    }

    fun post(
        path: String,
        body: ByteArray,
        contentType: String? = "application/octet-stream",
        extraHeaders: Map<String, String> = emptyMap()
    ): Response {
        ensureConnected()
        val out = output!!
        val header = buildString {
            append("POST $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("User-Agent: AirPlay/950.7.1\r\n")
            for ((k, v) in extraHeaders) {
                append("$k: $v\r\n")
            }
            append("Connection: keep-alive\r\n")
            append("\r\n")
        }
        try {
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            return readResponse(input!!)
        } catch (e: Exception) {
            // 连接可能被对端关闭：重连一次再试（仅对无状态请求安全，配对场景由上层保证顺序）
            Log.w(TAG, "post failed, retry once: ${e.message}")
            close()
            ensureConnected()
            val out2 = output!!
            out2.write(header.toByteArray(Charsets.US_ASCII))
            out2.write(body)
            out2.flush()
            return readResponse(input!!)
        }
    }

    /**
     * 移交底层连接的 I/O 流（用于配对完成后，由 HAP 加密通道接管同一条 TCP 连接）。
     * 移交后本客户端不再关闭这些流（close() 仍会尝试关 socket，外部应自行管理）。
     */
    fun detachStreams(): Pair<BufferedInputStream, OutputStream> {
        ensureConnected()
        return input!! to output!!
    }

    /** 返回底层 socket（供加密通道直接收发，避免缓冲层干扰）。 */
    fun rawSocket(): Socket {
        ensureConnected()
        return socket!!
    }

    /** 关闭持久连接。配对/会话结束后调用。 */
    fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
    }

    private fun readResponse(input: BufferedInputStream): Response {
        // 读取 header 部分（到 \r\n\r\n）
        val headerBytes = ArrayList<Byte>()
        var matchCount = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            headerBytes.add(b.toByte())
            when {
                matchCount == 0 && b == '\r'.code -> matchCount = 1
                matchCount == 1 && b == '\n'.code -> matchCount = 2
                matchCount == 2 && b == '\r'.code -> matchCount = 3
                matchCount == 3 && b == '\n'.code -> matchCount = 4
                else -> matchCount = 0
            }
            if (matchCount == 4) break
        }

        val headerText = String(headerBytes.toByteArray(), Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val statusLine = lines.firstOrNull() ?: ""
        // 兼容 HTTP/1.1 与 RTSP/1.0 两种状态行
        val statusCode = Regex("(?:HTTP|RTSP)/1\\.[01]\\s+(\\d+)").find(statusLine)
            ?.groupValues?.get(1)?.toIntOrNull() ?: -1

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        Log.d(TAG, "Response status=$statusCode bodyLen=$read")
        return Response(statusCode, headers, body.copyOf(read))
    }

    companion object {
        private const val TAG = "AirplayHttpClient"
    }
}