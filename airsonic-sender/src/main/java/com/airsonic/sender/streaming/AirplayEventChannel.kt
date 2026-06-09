// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import com.airsonic.sender.pairing.CryptoPrimitives
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * AirPlay 2 事件通道（Event Channel）。
 *
 * 这是真设备出声的**关键缺失环节**（对齐 pyatv `EventChannel`）：
 * SETUP#1 返回 eventPort 后，发送端必须**另开一条独立 TCP 连接**到 `device:eventPort`，
 * 设备会在这条连接上**主动推送** `POST /command RTSP/1.0`（携带 bufferStream/能力协商等）。
 * 发送端只需对每条 POST 回 `200 OK`（Content-Length:0, Audio-Latency:0）。
 *
 * 若不建立此通道：设备会**扣留 RECORD 的响应**（我们之前现象：RECORD 无响应 + FLUSH 500），
 * 从而停在 Paused 不出声。建立后 RECORD/FLUSH 会立即 200，进入 Playing 出声。
 *
 * 加密：复用 HAP 帧格式（同 [HapEncryptedChannel]），但密钥用 **Events-*** 派生，且**方向相反**
 * （设备在事件通道上扮演 client，故发送端的 send 用 "Events-Read"、recv 用 "Events-Write"）：
 *   - 对齐 pyatv：`setup_channel(EventChannel, ..., EVENTS_SALT, EVENTS_READ_INFO, EVENTS_WRITE_INFO)`
 *     即 out_key=Read、in_key=Write。
 */
class AirplayEventChannel(
    private val host: String,
    private val eventPort: Int,
    sessionKey: ByteArray,
    private val onLog: (String) -> Unit = {}
) {
    // 注意方向：发送端 send 用 Read key，recv 用 Write key（与控制通道相反）。
    private val writeKey = CryptoPrimitives.hkdfSha512(
        ikm = sessionKey,
        salt = SALT,
        info = "Events-Read-Encryption-Key".toByteArray(Charsets.US_ASCII),
        length = 32
    )
    private val readKey = CryptoPrimitives.hkdfSha512(
        ikm = sessionKey,
        salt = SALT,
        info = "Events-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
        length = 32
    )

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var outCount = 0L
    private var inCount = 0L

    private var thread: Thread? = null
    @Volatile private var running = false

    /** 连接到 device:eventPort 并启动后台收发循环。失败返回 false（不致命，继续尝试推流）。 */
    fun start(): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, eventPort), 5000)
            s.soTimeout = 0 // 阻塞读：等待设备推送
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()
            onLog("事件通道已连接 → $host:$eventPort（吸收设备 POST /command）")
            running = true
            thread = Thread({ loop() }, "airplay-event-channel").apply {
                isDaemon = true
                start()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "event channel connect failed", t)
            onLog("事件通道连接失败: ${t.message}（继续）")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    private fun loop() {
        val buffer = StringBuilder()
        var raw = ByteArray(0)
        try {
            while (running) {
                val block = readOneBlock() ?: break
                raw += block
                // 逐条解析设备推送的 RTSP 请求（POST /command ...），并回 200。
                while (true) {
                    val sep = indexOfCrlfCrlf(raw)
                    if (sep < 0) break
                    val headerText = String(raw, 0, sep, Charsets.US_ASCII)
                    val cl = Regex("(?i)content-length:\\s*(\\d+)")
                        .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val total = sep + 4 + cl
                    if (raw.size < total) break // body 未收齐，等下一块
                    // 取出 CSeq 以原样回填
                    val cseq = Regex("(?i)CSeq:\\s*(\\d+)")
                        .find(headerText)?.groupValues?.get(1)
                    val firstLine = headerText.split("\r\n").firstOrNull() ?: ""
                    onLog("事件通道收到: ${firstLine.take(40)}${if (cseq != null) " CSeq=$cseq" else ""} → 回 200")
                    sendResponse(cseq)
                    raw = raw.copyOfRange(total, raw.size)
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "event channel loop error", t)
        }
    }

    /** 回一个 200 OK（Content-Length:0, Audio-Latency:0），对齐 pyatv EventChannel。 */
    private fun sendResponse(cseq: String?) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 200 OK\r\n")
        sb.append("Content-Length: 0\r\n")
        sb.append("Audio-Latency: 0\r\n")
        sb.append("Server: AirTunes/950.7.1\r\n")
        if (cseq != null) sb.append("CSeq: $cseq\r\n")
        sb.append("\r\n")
        sendEncrypted(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun sendEncrypted(plaintext: ByteArray) {
        val out = output ?: return
        var off = 0
        do {
            val end = minOf(off + MAX_BLOCK, plaintext.size)
            val chunk = plaintext.copyOfRange(off, end)
            val lenLE = byteArrayOf(
                (chunk.size and 0xFF).toByte(),
                ((chunk.size shr 8) and 0xFF).toByte()
            )
            val nonce = nonce12(outCount)
            val ct = CryptoPrimitives.chacha20Poly1305Encrypt(writeKey, nonce, chunk, lenLE)
            out.write(lenLE)
            out.write(ct)
            outCount++
            off = end
        } while (off < plaintext.size)
        out.flush()
    }

    private fun readOneBlock(): ByteArray? {
        val lenLE = readExact(2) ?: return null
        val blockLen = (lenLE[0].toInt() and 0xFF) or ((lenLE[1].toInt() and 0xFF) shl 8)
        val cipher = readExact(blockLen + 16) ?: return null
        val nonce = nonce12(inCount)
        val pt = CryptoPrimitives.chacha20Poly1305Decrypt(readKey, nonce, cipher, lenLE)
        inCount++
        return pt
    }

    private fun readExact(n: Int): ByteArray? {
        val inp = input ?: return null
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = inp.read(buf, read, n - read)
            if (r == -1) return if (read == 0) null else buf.copyOf(read)
            read += r
        }
        return buf
    }

    private fun nonce12(counter: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) out[4 + i] = ((counter shr (8 * i)) and 0xFF).toByte()
        return out
    }

    private fun indexOfCrlfCrlf(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() && data[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    companion object {
        private const val TAG = "AirplayEventChannel"
        private const val MAX_BLOCK = 0x400
        private val SALT = "Events-Salt".toByteArray(Charsets.US_ASCII)

        /**
         * 派生音频 shared key（对齐 pyatv `setup_audio_stream`）：
         *   out_key, _ = encryption_keys(EVENTS_SALT, EVENTS_WRITE_INFO, EVENTS_READ_INFO)
         *   shared_secret = out_key[0:32]
         * 即 HKDF(ikm=SRP sessionKey, salt="Events-Salt", info="Events-Write-Encryption-Key")。
         *
         * 这个 key 同时用于：
         *   1) SETUP#2 的 shk 字段（告诉设备用它解密音频）
         *   2) 音频 RTP 包的 ChaCha20-Poly1305 加密
         * 两者必须一致，否则设备解密失败 → 全丢 → 无声。
         */
        fun deriveAudioSharedKey(sessionKey: ByteArray): ByteArray =
            CryptoPrimitives.hkdfSha512(
                ikm = sessionKey,
                salt = SALT,
                info = "Events-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
                length = 32
            )
    }
}