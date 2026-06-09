// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class LocalMediaHttpServerTest {
    private fun source(data: ByteArray) = object : RangeSource {
        override val length = data.size.toLong()
        override val mimeType = "video/mp4"
        override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }

    @Test fun servesFullBody() {
        val data = ByteArray(1000) { it.toByte() }
        val server = LocalMediaHttpServer(source(data)); val port = server.start()
        try {
            val c = URL("http://127.0.0.1:$port${server.path}").openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertEquals("video/mp4", c.contentType)
            val got = c.inputStream.readBytes()
            assertEquals(1000, got.size)
            assertTrue(got.contentEquals(data))
        } finally { server.stop() }
    }

    @Test fun servesRange() {
        val data = ByteArray(1000) { it.toByte() }
        val server = LocalMediaHttpServer(source(data)); val port = server.start()
        try {
            val c = URL("http://127.0.0.1:$port${server.path}").openConnection() as HttpURLConnection
            c.setRequestProperty("Range", "bytes=100-199")
            assertEquals(206, c.responseCode)
            assertEquals("bytes 100-199/1000", c.getHeaderField("Content-Range"))
            val got = c.inputStream.readBytes()
            assertEquals(100, got.size)
            assertEquals(100.toByte(), got[0])
        } finally { server.stop() }
    }
}