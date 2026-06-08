package com.airsonic.sender.streaming

import com.airsonic.sender.pairing.CryptoPrimitives
import org.junit.Assert.assertEquals
import org.junit.Test

/** 与 pyatv HAPSession 加密互通校验（参照向量由 pyatv 的 ChaCha20Poly1305+HKDF-SHA512 生成）。 */
class CryptoInteropTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun controlWriteKeyMatchesPyatv() {
        val shared = ByteArray(32) { 0x11 }
        val wkey = CryptoPrimitives.hkdfSha512(
            ikm = shared,
            salt = "Control-Salt".toByteArray(Charsets.US_ASCII),
            info = "Control-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
            length = 32
        )
        assertEquals("118900dfdb092adfcce159c995da743a30b438ba6c973ef165fb4a1662bb0131", hex(wkey))
    }

    @Test fun chachaCiphertextMatchesPyatv() {
        val wkey = CryptoPrimitives.hkdfSha512(
            ikm = ByteArray(32) { 0x11 },
            salt = "Control-Salt".toByteArray(Charsets.US_ASCII),
            info = "Control-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
            length = 32
        )
        val pt = "SETUP rtsp://x/1 RTSP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val lenLE = byteArrayOf((pt.size and 0xFF).toByte(), ((pt.size shr 8) and 0xFF).toByte())
        val nonce = ByteArray(12) // [0000]+counter(0) 全零
        val ct = CryptoPrimitives.chacha20Poly1305Encrypt(wkey, nonce, pt, lenLE)
        assertEquals("2a9f52771f9cc4395896ef43247d1bfb87f11fdde09b7e18ce10e6c480acea7492d72888b720ddd71b1dfde355", hex(ct))
    }
}
