// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.pairing

import android.util.Log

/**
 * Phase 0 验证点 1：AirPlay2 配对握手完整实现（pair-setup / pair-verify）。
 *
 * 协议参数与本地测试接收端（mini_pair_receiver.py）逐字段对齐，并已通过
 * Python 自测客户端验证：
 *  - SRP-6a: N=3072bit, g=5, SHA-512, username="Pair-Setup"
 *  - setup 加密: HKDF-SHA512, salt="Pair-Setup-Encrypt-Salt", nonce="PS-Msg05/06"
 *  - 设备签名: Ed25519, salt="Pair-Setup-Controller-Sign-Salt"
 *  - verify: X25519 ECDH + HKDF salt="Pair-Verify-Encrypt-Salt", nonce="PV-Msg02/03"
 *
 * @param deviceId   本机设备标识（如稳定的伪 MAC / 安装 ID），写入配对身份。
 * @param ltSeed     Ed25519 长期私钥种子（32B）。同一设备应持久化复用，
 *                   以便配对一次后 pair-verify 复用身份。null 则随机生成。
 */
class PairingHandshake(
    private val host: String,
    private val port: Int,
    private val deviceId: String = "AirSonic-Android",
    ltSeed: ByteArray? = null
) {
    private val http = AirplayHttpClient(host, port)

    // 设备长期 Ed25519 身份
    private val ltKeyPair = if (ltSeed != null && ltSeed.size == 32) {
        CryptoPrimitives.ed25519PrivateFromSeed(ltSeed)
    } else {
        CryptoPrimitives.generateEd25519KeyPair()
    }

    /** 本机长期公钥（LTPK），首次配对后接收端会记住它。 */
    val deviceLtpk: ByteArray get() = ltKeyPair.publicBytes

    /** 暴露底层 HTTP 客户端：transient 配对完成后，复用同一 TCP 连接做加密 RTSP。 */
    val httpClient: AirplayHttpClient get() = http

    /** pair-verify 成功后建立的会话密钥（32B），供后续 RTP 加密密钥派生使用。null 表示尚未完成。 */
    var sessionKey: ByteArray? = null
        private set

    /** PIN pair-setup M6 验签通过的接收端 LTPK——调用方应按 host 持久化，供日后 pair-verify 验签。 */
    var lastAccessoryLtpk: ByteArray? = null
        private set

    /** 已知的接收端 LTPK（来自首次 PIN 配对的持久化）。设置后 pair-verify 将强制验 M2 签名，防中间人/冒充。 */
    var knownAccessoryLtpk: ByteArray? = null

    sealed class Step {
        data class Info(val message: String) : Step()
        data class Success(val message: String) : Step()
        data class Failure(val message: String, val cause: Throwable? = null) : Step()
    }

    /**
     * pair-pin-start：请求接收端（Apple TV 等）在屏幕显示 4 位配对 PIN。
     * 之后用该 PIN 走非 transient pairSetup(pin)。实测 Apple TV 返回 200。
     */
    fun pairPinStart(): Boolean = runCatching {
        http.post("/pair-pin-start", ByteArray(0), extraHeaders = mapOf("X-Apple-HKP" to "3")).statusCode in 200..299
    }.getOrDefault(false)

    /**
     * pair-setup（带 PIN，首次配对）完整 SRP-6a 实现。
     *
     * M1 (S→R): state=1, method=0
     * M2 (R→S): state=2, salt, B
     * M3 (S→R): state=3, A, M1(proof)
     * M4 (R→S): state=4, M2(proof)          ← 双向认证完成
     * M5 (S→R): state=5, encrypted(设备身份: id+ltpk+ed25519签名)
     * M6 (R→S): state=6, encrypted(接收端身份)
     */
    fun pairSetup(
        pin: String,
        onStep: (Step) -> Unit,
        transient: Boolean = false,
        /** 一次性 PIN 配对（Apple TV statusFlags 含 OneTimePairingRequired）：用真实 PIN 走
         *  M1–M4 建立会话即止，不交换长期身份(M5/M6)、不持久化。下次需重新输 PIN。 */
        oneTime: Boolean = false
    ): Boolean {
        return try {
            val modeLabel = if (transient) "transient" else "PIN"
            onStep(Step.Info("开始 pair-setup（$modeLabel 模式），目标 $host:$port"))

            // /pair-setup 必须带 X-Apple-HKP 头。实测：HomePod/Sonos/小米 用 "4" M1→M2 正常；
            // Apple TV 用 "4" 返回 470 Connection Authorization Required，需用 "3"。
            // 策略：先试 "4"，若 M1 返回 470 自动回退 "3" 重发（设备无关，自动兼容）。
            var pairHeaders = mapOf("X-Apple-HKP" to "4")

            // ---- M1 → M2 ----
            // transient 模式（无屏设备 / 不弹 PIN）：state=1, method=0, flags=0x00000010（4B 大端）。
            // 非 transient（经典 PIN 首配）：state=1, method=0。
            val m1 = if (transient) {
                Tlv8.encode(
                    listOf(
                        Tlv8.Type.STATE to byteArrayOf(0x01),
                        Tlv8.Type.METHOD to byteArrayOf(0x00),
                        Tlv8.Type.FLAGS to byteArrayOf(0x00, 0x00, 0x00, 0x10)
                    )
                )
            } else {
                Tlv8.encode(
                    listOf(
                        Tlv8.Type.STATE to byteArrayOf(0x01),
                        Tlv8.Type.METHOD to byteArrayOf(0x00)
                    )
                )
            }
            onStep(Step.Info("发送 M1 (state=1, method=0${if (transient) ", flags=transient" else ""})"))
            var resp1 = http.post("/pair-setup", m1, extraHeaders = pairHeaders)
            if (resp1.statusCode == 470) {
                onStep(Step.Info("M1 返回 470，改用 X-Apple-HKP: 3 重试（Apple TV 等）"))
                pairHeaders = mapOf("X-Apple-HKP" to "3")
                resp1 = http.post("/pair-setup", m1, extraHeaders = pairHeaders)
            }
            if (resp1.statusCode !in 200..299) {
                onStep(Step.Failure("pair-setup M2 状态异常: ${resp1.statusCode}"))
                return false
            }
            val tlv2 = Tlv8.decode(resp1.body)
            val salt = tlv2[Tlv8.Type.SALT]
            val serverPub = tlv2[Tlv8.Type.PUBLIC_KEY]
            if (salt == null || serverPub == null) {
                onStep(Step.Failure("M2 缺少 salt/serverPub，keys=${tlv2.keys}"))
                return false
            }
            onStep(Step.Info("收到 M2: salt=${salt.size}B, B=${serverPub.size}B"))

            // ---- SRP-6a 客户端计算 ----
            val srp = CryptoPrimitives.Srp6Client(
                username = "Pair-Setup".toByteArray(Charsets.US_ASCII),
                password = pin.toByteArray(Charsets.US_ASCII)
            )
            val clientProof = srp.process(salt, serverPub)
            onStep(Step.Info("SRP 计算完成：A=${srp.publicA.size}B, 客户端证明 M1=${clientProof.size}B"))

            // ---- M3 → M4 ----
            val m3 = Tlv8.encode(
                listOf(
                    Tlv8.Type.STATE to byteArrayOf(0x03),
                    Tlv8.Type.PUBLIC_KEY to srp.publicA,
                    Tlv8.Type.PROOF to clientProof
                )
            )
            onStep(Step.Info("发送 M3 (state=3, A, proof)"))
            val resp3 = http.post("/pair-setup", m3, extraHeaders = pairHeaders)
            if (resp3.statusCode !in 200..299) {
                onStep(Step.Failure("pair-setup M4 状态异常: ${resp3.statusCode}"))
                return false
            }
            val tlv4 = Tlv8.decode(resp3.body)
            tlv4[Tlv8.Type.ERROR]?.let {
                onStep(Step.Failure("M4 接收端返回错误 (PIN 可能不正确): error=${it.firstOrNull()?.toInt()}"))
                return false
            }
            val serverProof = tlv4[Tlv8.Type.PROOF]
            if (serverProof == null || !srp.verifyServerProof(serverProof)) {
                onStep(Step.Failure("M4 服务器证明 M2 校验失败"))
                return false
            }
            onStep(Step.Success("M4 ✓ SRP 双向认证通过！会话密钥 K 已建立"))

            // transient / 一次性 PIN 模式：M1-M4 完成即建立加密会话，不交换长期身份（不走 M5/M6）。
            // 以 SRP 会话密钥作为后续 RTSP 控制通道 / 音频加密密钥派生的根。
            if (transient || oneTime) {
                sessionKey = srp.sessionKey
                onStep(Step.Success("pair-setup (${if (transient) "transient" else "oneTime-PIN"}) 完成 ✓ 会话已建立 (K=${srp.sessionKey.size}B)"))
                return true
            }

            // ---- M5 → M6：交换 Ed25519 设备身份 ----
            val encryptKey = CryptoPrimitives.hkdfSha512(
                ikm = srp.sessionKey,
                salt = "Pair-Setup-Encrypt-Salt".toByteArray(),
                info = "Pair-Setup-Encrypt-Info".toByteArray(),
                length = 32
            )
            val deviceIdBytes = deviceId.toByteArray(Charsets.US_ASCII)
            val deviceX = CryptoPrimitives.hkdfSha512(
                ikm = srp.sessionKey,
                salt = "Pair-Setup-Controller-Sign-Salt".toByteArray(),
                info = "Pair-Setup-Controller-Sign-Info".toByteArray(),
                length = 32
            )
            val deviceInfo = CryptoPrimitives.concat(deviceX, deviceIdBytes, deviceLtpk)
            val deviceSig = CryptoPrimitives.ed25519Sign(ltKeyPair.privateKey, deviceInfo)
            val subTlv = Tlv8.encode(
                listOf(
                    Tlv8.Type.IDENTIFIER to deviceIdBytes,
                    Tlv8.Type.PUBLIC_KEY to deviceLtpk,
                    Tlv8.Type.SIGNATURE to deviceSig
                )
            )
            val encrypted5 = CryptoPrimitives.chacha20Poly1305Encrypt(
                key = encryptKey,
                nonce = CryptoPrimitives.nonce12("PS-Msg05"),
                plaintext = subTlv
            )
            val m5 = Tlv8.encode(
                listOf(
                    Tlv8.Type.STATE to byteArrayOf(0x05),
                    Tlv8.Type.ENCRYPTED_DATA to encrypted5
                )
            )
            onStep(Step.Info("发送 M5 (state=5, 加密设备身份 ${subTlv.size}B)"))
            val resp5 = http.post("/pair-setup", m5, extraHeaders = pairHeaders)
            if (resp5.statusCode !in 200..299) {
                onStep(Step.Failure("pair-setup M6 状态异常: ${resp5.statusCode}"))
                return false
            }
            val tlv6 = Tlv8.decode(resp5.body)
            tlv6[Tlv8.Type.ERROR]?.let {
                onStep(Step.Failure("M6 接收端拒绝设备签名: error=${it.firstOrNull()?.toInt()}"))
                return false
            }
            val encrypted6 = tlv6[Tlv8.Type.ENCRYPTED_DATA]
            if (encrypted6 == null) {
                onStep(Step.Failure("M6 缺少加密接收端身份"))
                return false
            }
            // 解密并验证接收端身份
            val dec6 = CryptoPrimitives.chacha20Poly1305Decrypt(
                key = encryptKey,
                nonce = CryptoPrimitives.nonce12("PS-Msg06"),
                ciphertext = encrypted6
            )
            val sub6 = Tlv8.decode(dec6)
            val accId = sub6[Tlv8.Type.IDENTIFIER]
            val accLtpk = sub6[Tlv8.Type.PUBLIC_KEY]
            val accSig = sub6[Tlv8.Type.SIGNATURE]
            if (accId == null || accLtpk == null || accSig == null) {
                onStep(Step.Failure("M6 接收端身份字段不全"))
                return false
            }
            val accX = CryptoPrimitives.hkdfSha512(
                ikm = srp.sessionKey,
                salt = "Pair-Setup-Accessory-Sign-Salt".toByteArray(),
                info = "Pair-Setup-Accessory-Sign-Info".toByteArray(),
                length = 32
            )
            val accInfo = CryptoPrimitives.concat(accX, accId, accLtpk)
            if (!CryptoPrimitives.ed25519Verify(accLtpk, accInfo, accSig)) {
                onStep(Step.Failure("M6 接收端身份签名验证失败"))
                return false
            }
            lastAccessoryLtpk = accLtpk   // 已验签的接收端长期公钥，交调用方持久化
            onStep(Step.Success("pair-setup 完成 ✓ 接收端身份已验证 (id=${String(accId)})"))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "pairSetup error", t)
            onStep(Step.Failure("pair-setup 异常: ${t.message}", t))
            false
        }
    }

    /**
     * pair-verify（已配对设备的快速握手，建立加密会话）。
     *
     * M1 (S→R): state=1, 我方 X25519 临时公钥
     * M2 (R→S): state=2, 对方临时公钥 + 加密(接收端身份+签名)
     * M3 (S→R): state=3, 加密(我方身份+签名)
     * M4 (R→S): state=4                         ← 加密会话建立
     */
    fun pairVerify(onStep: (Step) -> Unit): Boolean {
        return try {
            onStep(Step.Info("开始 pair-verify，目标 $host:$port"))

            // ---- M1：临时 X25519 ----
            val ephemeral = CryptoPrimitives.generateX25519KeyPair()
            val m1 = Tlv8.encode(
                listOf(
                    Tlv8.Type.STATE to byteArrayOf(0x01),
                    Tlv8.Type.PUBLIC_KEY to ephemeral.publicBytes
                )
            )
            onStep(Step.Info("发送 M1 (state=1, X25519 公钥 ${ephemeral.publicBytes.size}B)"))
            val resp1 = http.post("/pair-verify", m1, extraHeaders = mapOf("X-Apple-HKP" to "3"))
            if (resp1.statusCode !in 200..299) {
                onStep(Step.Failure("pair-verify M2 状态异常: ${resp1.statusCode}"))
                return false
            }
            val tlv2 = Tlv8.decode(resp1.body)
            val theirPublic = tlv2[Tlv8.Type.PUBLIC_KEY]
            val encrypted2 = tlv2[Tlv8.Type.ENCRYPTED_DATA]
            if (theirPublic == null || encrypted2 == null) {
                onStep(Step.Failure("M2 缺少 public_key/encrypted，keys=${tlv2.keys}"))
                return false
            }
            onStep(Step.Info("收到 M2: 对方公钥 ${theirPublic.size}B, 加密块 ${encrypted2.size}B"))

            // ---- ECDH + HKDF 会话密钥 ----
            val shared = CryptoPrimitives.x25519SharedSecret(ephemeral.privateKey, theirPublic)
            val sessionKey = CryptoPrimitives.hkdfSha512(
                ikm = shared,
                salt = "Pair-Verify-Encrypt-Salt".toByteArray(),
                info = "Pair-Verify-Encrypt-Info".toByteArray(),
                length = 32
            )

            // 解密 M2 并验证接收端签名（acc_pub + acc_id + our_pub）
            val dec2 = CryptoPrimitives.chacha20Poly1305Decrypt(
                key = sessionKey,
                nonce = CryptoPrimitives.nonce12("PV-Msg02"),
                ciphertext = encrypted2
            )
            val sub2 = Tlv8.decode(dec2)
            val accId = sub2[Tlv8.Type.IDENTIFIER]
            val accSig = sub2[Tlv8.Type.SIGNATURE]
            // 验接收端签名（accInfo = 对方临时公钥 + 对方身份 + 我方临时公钥）：
            // 没这步 pair-verify 退化成无认证 ECDH，可被冒充设备中间人。LTPK 来自首次 PIN 配对(M6 已验签)。
            val known = knownAccessoryLtpk
            if (known != null) {
                if (accId == null || accSig == null) {
                    onStep(Step.Failure("M2 缺少接收端身份/签名（已存 LTPK 时必须携带）"))
                    return false
                }
                val accInfo = CryptoPrimitives.concat(theirPublic, accId, ephemeral.publicBytes)
                if (!CryptoPrimitives.ed25519Verify(known, accInfo, accSig)) {
                    onStep(Step.Failure("M2 接收端签名验证失败——对方非首次配对的设备（可能被冒充），中止"))
                    return false
                }
                onStep(Step.Info("M2 接收端签名验证通过 ✓ (id=${String(accId)})"))
            } else if (accId != null) {
                onStep(Step.Info("M2 接收端身份 id=${String(accId)}（本机未存该设备 LTPK，跳过验签；重新 PIN 配对后启用）"))
            }
            onStep(Step.Info("ECDH + HKDF 会话密钥派生完成 (${sessionKey.size}B)"))

            // ---- M3：我方加密身份签名（our_pub + device_id + their_pub）----
            val deviceIdBytes = deviceId.toByteArray(Charsets.US_ASCII)
            val ourInfo = CryptoPrimitives.concat(ephemeral.publicBytes, deviceIdBytes, theirPublic)
            val ourSig = CryptoPrimitives.ed25519Sign(ltKeyPair.privateKey, ourInfo)
            val sub3 = Tlv8.encode(
                listOf(
                    Tlv8.Type.IDENTIFIER to deviceIdBytes,
                    Tlv8.Type.SIGNATURE to ourSig
                )
            )
            val encrypted3 = CryptoPrimitives.chacha20Poly1305Encrypt(
                key = sessionKey,
                nonce = CryptoPrimitives.nonce12("PV-Msg03"),
                plaintext = sub3
            )
            val m3 = Tlv8.encode(
                listOf(
                    Tlv8.Type.STATE to byteArrayOf(0x03),
                    Tlv8.Type.ENCRYPTED_DATA to encrypted3
                )
            )
            onStep(Step.Info("发送 M3 (state=3, 加密我方身份签名)"))
            val resp3 = http.post("/pair-verify", m3, extraHeaders = mapOf("X-Apple-HKP" to "3"))
            if (resp3.statusCode !in 200..299) {
                onStep(Step.Failure("pair-verify M4 状态异常: ${resp3.statusCode}"))
                return false
            }
            val tlv4 = Tlv8.decode(resp3.body)
            tlv4[Tlv8.Type.ERROR]?.let {
                onStep(Step.Failure("M4 接收端验证我方签名失败: error=${it.firstOrNull()?.toInt()}"))
                return false
            }
            onStep(Step.Success("pair-verify 完成 ✓ 加密会话已建立（可用于 RTSP 加密通道）"))
            // 关键：控制通道密钥须从【原始 ECDH 共享密钥】派生(HKDF Control-Salt/...)，
            // 而非 Pair-Verify-Encrypt 密钥(那只用于解 M2/M3)。设错会导致通道双方密钥不一致→对端不回应。
            this.sessionKey = shared
            true
        } catch (t: Throwable) {
            Log.e(TAG, "pairVerify error", t)
            onStep(Step.Failure("pair-verify 异常: ${t.message}", t))
            false
        }
    }

    companion object {
        private const val TAG = "PairingHandshake"
    }
}