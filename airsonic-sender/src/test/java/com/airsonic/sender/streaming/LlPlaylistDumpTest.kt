// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Test
import java.net.URL

/** 一次性解剖：喂 3 个 1s 分片（假 TS 包），打印 LL 播放列表原文，供逐行核对 spec。需 /tmp/jvm_harness_go。 */
class LlPlaylistDumpTest {
    @Test fun dump() {
        if (!java.io.File("/tmp/jvm_harness_go").exists()) { println("skip (no go marker)"); return }
        val hls = HlsLiveServer(minSegmentUs = 400_000, baseId = "dump")
        val port = hls.start()
        val pkt = ByteArray(188) { 0x47 }
        var pts = 1_000_000L
        hls.boundary(pts)   // 开第 0 片
        repeat(3) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 1000) {
                repeat(20) { hls.acceptPacket(pkt) }
                Thread.sleep(50)
            }
            pts += 1_000_000
            hls.boundary(pts)
        }
        val text = URL("http://127.0.0.1:$port${hls.playlistPath}").readText()
        println("=== PLAYLIST ===\n$text=================")
        hls.stop()
    }
}
