// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** HlsLiveServer：切片边界/播放列表格式/滑动窗口 + HTTP 服务行为。 */
class HlsLiveServerTest {

    @Test
    fun `边界切片与播放列表格式`() {
        val hls = HlsLiveServer(windowSize = 3)
        val port = hls.start()
        // 首个关键帧开第 0 片；不足 minSegmentUs 的边界被忽略
        hls.boundary(0)
        hls.acceptPacket(ByteArray(188) { 0x47 })
        hls.boundary(500_000)                       // < 800ms → 忽略
        hls.acceptPacket(ByteArray(188) { 0x47 })
        hls.boundary(1_000_000)                     // 关闭片0（时长 1s）
        assertEquals(1, hls.closedSegments)
        hls.acceptPacket(ByteArray(188) { 0x47 })
        hls.boundary(2_000_000)                     // 关闭片1
        assertEquals(2, hls.closedSegments)

        val pl = fetch(port, hls.playlistPath)
        assertTrue(pl.startsWith("#EXTM3U"))
        assertTrue(pl.contains("#EXT-X-MEDIA-SEQUENCE:0"))
        assertTrue(pl.contains("#EXTINF:1.000000,"))
        assertTrue(pl.contains("seg0.ts"))
        assertTrue(pl.contains("seg1.ts"))
        // 分片可拉（播放列表里相对路径 → $base/seg0.ts）
        val seg = fetchBytes(port, hls.playlistPath.substringBeforeLast('/') + "/seg0.ts")
        assertEquals(2 * 188, seg.size)
        assertEquals(0x47.toByte(), seg[0])
        hls.stop()
    }

    @Test
    fun `滑动窗口丢弃最旧分片且 MEDIA-SEQUENCE 前进`() {
        val hls = HlsLiveServer(windowSize = 2)
        val port = hls.start()
        hls.boundary(0)
        for (i in 1..4) {
            hls.acceptPacket(ByteArray(188))
            hls.boundary(i * 1_000_000L)
        }
        assertEquals(4, hls.closedSegments)
        val pl = fetch(port, hls.playlistPath)
        assertTrue(pl.contains("#EXT-X-MEDIA-SEQUENCE:2"))   // 窗口 [2,3]
        assertTrue(!pl.contains("seg1.ts"))
        assertTrue(pl.contains("seg3.ts"))
        hls.stop()
    }

    private fun fetch(port: Int, path: String): String = String(fetchBytes(port, path), Charsets.US_ASCII)

    private fun fetchBytes(port: Int, path: String): ByteArray {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        return c.inputStream.readBytes()
    }
}

/** 常驻调试服务器：/tmp/jvm_harness_go 存在时发布 ffmpeg 分片并挂起，供 curl 解剖响应。 */
class HlsSleeperTest {
    @Test fun `常驻 HLS 服务器供 curl 解剖`() {
        if (!java.io.File("/tmp/jvm_harness_go").exists()) { println("skip"); return }
        val hls = HlsLiveServer(baseId = "debug")
        hls.verbatimDir = java.io.File("/tmp/airsonic-http/hls")
        val port = hls.start()
        val dir = java.io.File("/tmp/airsonic-http/hls")
        val t0 = System.currentTimeMillis()
        dir.listFiles { f -> f.name.matches(Regex("live\\d+\\.ts")) }.orEmpty()
            .sortedBy { it.name.removePrefix("live").removeSuffix(".ts").toLong() }
            .takeLast(6).forEachIndexed { i, f ->
                hls.publishSegment(f.readBytes(), 2_000_000, wallStartMs = t0 - (6 - i) * 2000)
            }
        println(">>> SLEEPER on $port playlist=${hls.playlistPath}")
        Thread.sleep(180_000)
        hls.stop()
    }
}
