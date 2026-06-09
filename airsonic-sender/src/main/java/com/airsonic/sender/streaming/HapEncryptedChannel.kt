// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import com.airsonic.sender.pairing.CryptoPrimitives
import java.io.BufferedInputStream
import java.io.OutputStream

/**
 * HAP（HomeKit Accessory Protocol）加密通道。
 *
 * transient pair-setup 成功后，AirPlay2 的 RTSP 控制通道立即转为**全程加密**。
 * 明文请求会被设备忽略/拒绝（实测 HomePod：明文 SETUP 无响应；加密 SETUP 返回 200）。
 *
 * 已在 Python 原型 + 真机 HomePod 验证通过的协议规格：
 *  - 密钥派生（HKDF-SHA512，ikm = SRP transient sessionKey K，salt = "Control-Salt"）
 *      发送密钥 writeKey = HKDF(info = "Control-Write-Encryption-Key", 32B)
 *      接收密钥 readKey  = HKDF(info = "Control-Read-Encryption-Key",  32B)
 *    （命名站在"设备读/写"视角：设备用 Write key 解我们发的帧，用 Read key 加密回我们的帧）
 *  - 帧格式：[2B 明文长度(小端, ≤0x400)] [密文] [16B Poly1305 tag]
 *      aad   = 那 2 字节长度本身
 *      nonce = 8B 帧计数器(小端) 左补 0 到 12B
 *      counter：收/发各自独立，从 0 起每帧 +1
 *  - 算法：ChaCha20-Poly1305；单块上限 0x400(1024)，超长分块。
 */
class HapEncryptedChannel(
    private val input: BufferedInputStream,
    private val output: OutputStream,
    sessionKey: ByteArray
) {
    private val writeKey = CryptoPrimitives.hkdfSha512(
        ikm = sessionKey,
        salt = SALT,
        info = "Control-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
        length = 32
    )
    private val readKey = CryptoPrimitives.hkdfSha512(
        ikm = sessionKey,
        salt = SALT,
        info = "Control-Read-Encryption-Key".toByteArray(Charsets.US_ASCII),
        length = 32
    )

    private var outCount = 0L
    private var inCount = 0L

    /** 把明文按 ≤1024 分块加密后写出。 */
    fun sendEncrypted(plaintext: ByteArray) {
        var off = 0
        do {
            val end = minOf(off + MAX_BLOCK, plaintext.size)
            val chunk = plaintext.copyOfRange(off, end)
            val lenLE = byteArrayOf((chunk.size and 0xFF).toByte(), ((chunk.size shr 8) and 0xFF).toByte())
            val nonce = nonce12(outCount)
            val ct = CryptoPrimitives.chacha20Poly1305Encrypt(writeKey, nonce, chunk, lenLE)
            output.write(lenLE)
            output.write(ct)
            outCount++
            off = end
        } while (off < plaintext.size)
        output.flush()
    }

    /**
     * 读取并解密，直到拼出一个完整的 RTSP 响应（header + body 按 Content-Length）。
     * 返回完整明文响应字节。
     */
    fun recvResponse(): ByteArray {
        var plain = ByteArray(0)
        while (true) {
            plain += readOneBlock() ?: break
            val sep = indexOfCrlfCrlf(plain)
            if (sep >= 0) {
                val headerText = String(plain, 0, sep, Charsets.US_ASCII)
                val cl = Regex("(?i)content-length:\\s*(\\d+)").find(headerText)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bodyStart = sep + 4
                if (plain.size - bodyStart >= cl) return plain
            }
        }
        return plain
    }

    /** 读取一个加密块并解密为明文（无更多数据时返回 null）。 */
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
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) return if (read == 0) null else buf.copyOf(read)
            read += r
        }
        return buf
    }

    private fun nonce12(counter: Long): ByteArray {
        val out = ByteArray(12)
        // 与 Python struct.pack("<Q", counter).rjust(12, "\x00") 对齐：
        // 8 字节小端计数器右对齐到 12 字节 → 占据 [4..11]，前 4 字节为 0。
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
        private const val TAG = "HapEncryptedChannel"
        private const val MAX_BLOCK = 0x400
        private val SALT = "Control-Salt".toByteArray(Charsets.US_ASCII)
    }
}