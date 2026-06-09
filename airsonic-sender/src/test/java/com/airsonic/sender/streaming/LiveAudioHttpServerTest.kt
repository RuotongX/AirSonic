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
}
