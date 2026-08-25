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
 * Mac 重配对 harness：pair-verify 失败（M4 error=2，接收端忘了我们）时用。
 * 触发 Mac 屏幕显示 PIN → 把 PIN 写进 /tmp/jvm_pin → 完成 pair-setup（沿用 /tmp/jvm_ltseed+pid 身份）。
 * 仅当 /tmp/jvm_harness_go 存在时运行；ATV_HOST 指定接收端。
 */
class MacPairHarnessTest {
    private val HOST = System.getenv("ATV_HOST") ?: "172.23.25.241"
    private val PORT = 7000

    @Test fun run() {
        if (!File("/tmp/jvm_harness_go").exists()) { println("PAIR-HARNESS skipped (no go marker)"); return }
        val seedFile = File("/tmp/jvm_ltseed"); val pidFile = File("/tmp/jvm_pid")
        val ltSeed = if (seedFile.exists()) seedFile.readBytes()
            else ByteArray(32).also { SecureRandom().nextBytes(it); seedFile.writeBytes(it) }
        val pid = if (pidFile.exists()) pidFile.readText().trim()
            else UUID.randomUUID().toString().also { pidFile.writeText(it) }
        val hs = PairingHandshake(HOST, PORT, pid, ltSeed)

        var useSplit = false
        if (!hs.pairPinStart()) {
            println(">>> pair-pin-start status=${hs.lastPinStartStatus}，改走 M1 触发显示")
            if (!hs.pairSetupBegin { println(">>> m1: $it") }) { println(">>> M1 失败"); return }
            useSplit = true
        }
        println(">>> 等待 /tmp/jvm_pin（Mac 屏幕上的 PIN 写入该文件）…")
        File("/tmp/jvm_pin").delete()
        val t0 = System.currentTimeMillis()
        var pin: String? = null
        while (System.currentTimeMillis() - t0 < 120_000) {
            val f = File("/tmp/jvm_pin")
            if (f.exists()) { pin = f.readText().trim().replace("-", ""); break }
            Thread.sleep(500)
        }
        if (pin.isNullOrEmpty()) { println(">>> 超时未等到 PIN"); return }
        println(">>> 用 PIN=$pin 完成 pair-setup (split=$useSplit)")
        val ok = if (useSplit) hs.pairSetupWithPin(pin) { println(">>> setup: $it") }
            else hs.pairSetup(pin, onStep = { println(">>> setup: $it") }, transient = false)
        println(">>> pairSetup = $ok")
        if (ok) {
            val hs2 = PairingHandshake(HOST, PORT, pid, ltSeed)
            println(">>> 复核 pairVerify = ${hs2.pairVerify { println(">>> verify: $it") }}")
        }
    }
}
