package com.airsonic.sender.streaming

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.Socket

class LocalMediaHttpServerDlnaTest {
    private class Bytes(private val data: ByteArray) : RangeSource {
        override val length get() = data.size.toLong()
        override val mimeType = "video/mp4"
        override fun open(offset: Long): InputStream = data.inputStream().apply { skip(offset) }
    }

    @Test fun responseIncludesDlnaHeaders() {
        val server = LocalMediaHttpServer(Bytes(ByteArray(1024)))
        val port = server.start()
        try {
            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write("GET ${server.path} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val resp = sock.getInputStream().bufferedReader().readText()
                assertTrue(resp.contains("transferMode.dlna.org: Streaming"))
                // 断言完整值，与 DlnaProtocol.DLNA_CONTENT_FEATURES 一致，防两处静默分叉。
                assertTrue(resp.contains(
                    "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"))
            }
        } finally { server.stop() }
    }
}
