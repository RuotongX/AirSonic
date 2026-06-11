// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.pairing

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * AirPlay2 / HomeKit 配对所需的加密原语封装（基于 BouncyCastle）。
 *
 * 覆盖：
 * - Curve25519 (X25519) ECDH —— 用于 pair-verify
 * - HKDF-SHA512 —— 派生会话/校验密钥
 * - ChaCha20-Poly1305 AEAD —— pair-verify / pair-setup 加密块、会话加密
 */
object CryptoPrimitives {

    private val rng = SecureRandom()

    // ---- X25519 ----

    class X25519KeyPair(
        val privateKey: X25519PrivateKeyParameters,
        val publicKey: X25519PublicKeyParameters
    ) {
        val publicBytes: ByteArray get() = publicKey.encoded
    }

    fun generateX25519KeyPair(): X25519KeyPair {
        val priv = X25519PrivateKeyParameters(rng)
        return X25519KeyPair(priv, priv.generatePublicKey())
    }

    /** 计算共享密钥（32 字节）。 */
    fun x25519SharedSecret(
        ourPrivate: X25519PrivateKeyParameters,
        theirPublic: ByteArray
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(ourPrivate)
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPublic, 0), out, 0)
        return out
    }

    // ---- HKDF-SHA512 ----

    fun hkdfSha512(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA512Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        hkdf.generateBytes(out, 0, length)
        return out
    }

    // ---- ChaCha20-Poly1305 ----

    /**
     * @param nonce 12 字节 nonce（AirPlay 配对常用 8 字节标签左补 0 到 12 字节）。
     */
    fun chacha20Poly1305Encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    fun chacha20Poly1305Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    /** 将 AirPlay 常用的字符串 nonce（如 "PV-Msg02"）转为 12 字节。 */
    fun nonce12(tag: String): ByteArray {
        val src = tag.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(12)
        // 左补 0：前 4 字节为 0，后 8 字节为标签
        System.arraycopy(src, 0, out, 12 - src.size, src.size)
        return out
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    // ---- Ed25519（设备长期身份签名）----

    class Ed25519KeyPair(
        val privateKey: Ed25519PrivateKeyParameters,
        val publicKey: Ed25519PublicKeyParameters
    ) {
        val publicBytes: ByteArray get() = publicKey.encoded
    }

    fun generateEd25519KeyPair(): Ed25519KeyPair {
        val priv = Ed25519PrivateKeyParameters(rng)
        return Ed25519KeyPair(priv, priv.generatePublicKey())
    }

    fun ed25519PrivateFromSeed(seed: ByteArray): Ed25519KeyPair {
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        return Ed25519KeyPair(priv, priv.generatePublicKey())
    }

    fun ed25519Sign(priv: Ed25519PrivateKeyParameters, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    // ---- SRP-6a (RFC 5054 风格，AirPlay/HomeKit: 3072bit, g=5, SHA-512) ----

    /**
     * SRP-6a 客户端。与已验证的测试接收端完全对齐：
     *  - N = 3072-bit MODP group, g = 5
     *  - Hash = SHA-512
     *  - k = H(N | g)         （PAD 到 N 长度）
     *  - x = H(salt | H(user | ":" | pin)）
     *  - A = g^a
     *  - u = H(A | B)          （PAD）
     *  - S = (B - k * g^x)^(a + u*x) mod N
     *  - K = H(S)
     *  - M1 = H( H(N) xor H(g) | H(user) | salt | A | B | K )
     *  - 服务器证明 M2 = H(A | M1 | K)
     */
    class Srp6Client(
        private val username: ByteArray,
        private val password: ByteArray
    ) {
        private val a: BigInteger = BigInteger(512, rng).mod(N)
        val A: BigInteger = g.modPow(a, N)

        lateinit var sessionKey: ByteArray
            private set
        private lateinit var m1: ByteArray

        val publicA: ByteArray get() = A.toMinimalBytes()

        /** 输入服务器返回的 salt 与 B，计算 K 与客户端证明 M1。 */
        fun process(saltBytes: ByteArray, bBytes: ByteArray): ByteArray {
            val salt = BigInteger(1, saltBytes)
            val B = BigInteger(1, bBytes)
            require(B.mod(N) != BigInteger.ZERO) { "SRP: 非法服务器公钥 B" }

            val k = hashInt(padN(N), padN(g))
            val u = hashInt(padN(A), padN(B))
            require(u.mod(N) != BigInteger.ZERO) { "SRP: 非法 u（u==0）" }   // RFC 5054 要求
            val xInner = sha512(concat(username, byteArrayOf(':'.code.toByte()), password))
            val x = hashInt(salt.toMinimalBytes(), xInner)

            // S = (B - k * g^x)^(a + u*x) mod N
            val gx = g.modPow(x, N)
            val base = B.subtract(k.multiply(gx)).mod(N)
            val exp = a.add(u.multiply(x))
            val S = base.modPow(exp, N)
            sessionKey = sha512(S.toMinimalBytes())

            // M1 = H( H(N) xor H(g) | H(user) | salt | A | B | K )
            val hN = BigInteger(1, sha512(N.toMinimalBytes()))
            val hG = BigInteger(1, sha512(g.toMinimalBytes()))
            val hXor = hN.xor(hG).toMinimalBytes()
            m1 = sha512(
                concat(
                    hXor,
                    sha512(username),
                    salt.toMinimalBytes(),
                    A.toMinimalBytes(),
                    B.toMinimalBytes(),
                    sessionKey
                )
            )
            return m1
        }

        /** 校验服务器证明 M2 = H(A | M1 | K)。 */
        fun verifyServerProof(m2: ByteArray): Boolean {
            val expected = sha512(concat(A.toMinimalBytes(), m1, sessionKey))
            return MessageDigest.isEqual(expected, m2)
        }

        private fun padN(v: BigInteger): ByteArray {
            val raw = v.toMinimalBytes()
            if (raw.size == PAD_L) return raw
            val out = ByteArray(PAD_L)
            System.arraycopy(raw, 0, out, PAD_L - raw.size, raw.size)
            return out
        }

        private fun hashInt(vararg parts: ByteArray): BigInteger =
            BigInteger(1, sha512(concat(*parts)))
    }

    fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, off, a.size)
            off += a.size
        }
        return out
    }

    /** BigInteger -> 最小无符号大端字节（去掉符号位多出的前导 0），与 Python int.to_bytes 对齐。 */
    fun BigInteger.toMinimalBytes(): ByteArray {
        if (this.signum() == 0) return ByteArray(0)
        val b = this.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    // SRP-6a 群参数（3072-bit MODP，AirPlay/HomeKit 标准）
    private val N: BigInteger = BigInteger(
        (
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
            "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
            "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
            "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
            "49286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8" +
            "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C" +
            "180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D" +
            "04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7D" +
            "B3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D226" +
            "1AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFC" +
            "E0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
        ),
        16
    )
    private val g: BigInteger = BigInteger.valueOf(5)
    private val PAD_L: Int = N.bitLength() / 8
}