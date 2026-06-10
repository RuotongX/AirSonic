// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.Socket

class LiveAudioHttpServerTest {
    private fun readLine(ins: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    @Test fun serves200ThenStreamsPushedFrames() {
        val server = LiveAudioHttpServer()
        val port = server.start()
        try {
            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write(
                    "GET ${server.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
                )
                sock.getOutputStream().flush()
                val ins = sock.getInputStream()
                val status = readLine(ins)
                assertTrue(status.contains("200"))
                var ctype = ""
                while (true) { val l = readLine(ins); if (l.isEmpty()) break; if (l.startsWith("Content-Type", true)) ctype = l }
                assertTrue(ctype.contains("audio/aac"))
                // 等订阅生效后推一帧
                Thread.sleep(100)
                server.push(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 1, 2, 3))
                val buf = ByteArray(5)
                var read = 0
                while (read < 5) { val n = ins.read(buf, read, 5 - read); if (n < 0) break; read += n }
                assertTrue(read == 5 && buf[0] == 0xFF.toByte() && buf[1] == 0xF1.toByte())
            }
        } finally { server.stop() }
    }

    @Test fun headRequest_getsHeadersOnly_noBody() {
        val server = LiveAudioHttpServer()
        val port = server.start()
        try {
            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write(
                    "HEAD ${server.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
                )
                sock.getOutputStream().flush()
                val ins = sock.getInputStream()
                assertTrue(readLine(ins).contains("200"))
                while (true) { val l = readLine(ins); if (l.isEmpty()) break }
                // HEAD 后连接应被服务端关闭且无 body：推帧也不会写过来
                server.push(byteArrayOf(1, 2, 3))
                sock.soTimeout = 1500
                val n = try { ins.read() } catch (_: java.net.SocketTimeoutException) { -2 }
                assertTrue("HEAD 不应收到 body (read=$n)", n == -1)
            }
        } finally { server.stop() }
    }

    @Test fun wavMode_sendsContentLengthAndRiffHeaderFirst() {
        val server = LiveAudioHttpServer(
            contentType = "audio/wav", pathExt = "wav",
            fakeContentLength = 0xFFFFFFFFL,
            streamHeader = wavStreamHeader(44100, 2, 16),
        )
        val port = server.start()
        try {
            assertTrue(server.path.endsWith(".wav"))
            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write(
                    "GET ${server.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
                )
                sock.getOutputStream().flush()
                val ins = sock.getInputStream()
                assertTrue(readLine(ins).contains("200"))
                var clen = ""; var ctype = ""
                while (true) {
                    val l = readLine(ins); if (l.isEmpty()) break
                    if (l.startsWith("Content-Length", true)) clen = l
                    if (l.startsWith("Content-Type", true)) ctype = l
                }
                assertTrue(ctype.contains("audio/wav"))
                assertTrue(clen.contains("4294967295"))
                // body 先到 44 字节 RIFF 头
                val head = ByteArray(4)
                var read = 0
                while (read < 4) { val n = ins.read(head, read, 4 - read); if (n < 0) break; read += n }
                assertTrue(read == 4 && String(head) == "RIFF")
            }
        } finally { server.stop() }
    }
}
