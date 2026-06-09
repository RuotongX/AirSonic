// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import com.airsonic.sender.pairing.PairingHandshake
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * JVM 抓包/定位入口：桌面 JVM 跑真实 配对+SETUP 打到 Apple TV，stdout 全可见。
 * 仅当 /tmp/jvm_harness_go 存在时运行。PIN 经 /tmp/jvm_pin 提供。
 * 身份持久化到 /tmp/jvm_ltseed + /tmp/jvm_pid → 已配对的 run 可直接 pair-verify(免PIN)。
 */
class AppleTvHarnessTest {
    private val HOST = "192.168.100.14"
    private val PORT = 7000
    private fun hex(b: ByteArray?) = b?.joinToString("") { "%02x".format(it) } ?: "null"

    @Test fun run() {
        if (!File("/tmp/jvm_harness_go").exists()) { println("HARNESS skipped (no go marker)"); return }

        val seedFile = File("/tmp/jvm_ltseed"); val pidFile = File("/tmp/jvm_pid")
        val ltSeed = if (seedFile.exists()) seedFile.readBytes()
            else ByteArray(32).also { SecureRandom().nextBytes(it); seedFile.writeBytes(it) }
        val pid = if (pidFile.exists()) pidFile.readText().trim()
            else UUID.randomUUID().toString().also { pidFile.writeText(it) }
        println("=== HARNESS pairingId=$pid ===")

        fun verify(tag: String): PairingHandshake? {
            val hs = PairingHandshake(HOST, PORT, pid, ltSeed)
            val ok = hs.pairVerify { println("$tag: $it") }
            println(">>> $tag pairVerify = $ok  sessionKey=${hex(hs.sessionKey)}")
            return if (ok) hs else null
        }

        // 1) 先直接 verify（若已配对则免 PIN）
        var hs = verify("verify-direct")

        // 2) 未配对 → pair-setup(PIN) → verify 重试(应对刚配对的竞态)
        if (hs == null) {
            println(">>> 需 pair-setup：把 PIN 写到 /tmp/jvm_pin（用 pyatv 触发 TV 显示 PIN）")
            File("/tmp/jvm_pin").delete()
            var pin: String? = null
            for (i in 0 until 600) { val f = File("/tmp/jvm_pin"); if (f.exists()) { pin = f.readText().trim(); break }; Thread.sleep(1000) }
            if (pin == null) { println("FAIL no PIN"); return }
            println(">>> using PIN=$pin")
            val setupHs = PairingHandshake(HOST, PORT, pid, ltSeed)
            val setupOk = setupHs.pairSetup(pin, onStep = { println("setup: $it") }, transient = false)
            println(">>> pairSetup = $setupOk")
            if (!setupOk) return
            for (attempt in 1..6) {
                Thread.sleep(800)
                hs = verify("verify-retry$attempt")
                if (hs != null) break
            }
        }
        if (hs == null) { println("FAIL pair-verify (始终失败)"); return }

        // 3) 完整视频流程：AirplayVideoController(SETUP+事件通道+RECORD) → /play
        val ctl = AirplayVideoController(HOST, hs)
        val conn = ctl.connect()
        println(">>> connect(SETUP+event+RECORD) = $conn  lastStatus=${ctl.lastStatus} lastError=${ctl.lastError}")
        if (conn) {
            val url = "http://192.168.100.69:8888/sample.mp4"
            val ok = ctl.play(url, 0.0)
            println(">>> /play($url) = $ok  lastStatus=${ctl.lastStatus} lastError=${ctl.lastError}")
            // 让 TV 有时间拉流播放
            Thread.sleep(25000)
            println(">>> 观察 TV 是否出画面（8s 内）")
        }
    }
}