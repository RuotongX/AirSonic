// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 的 AES-GCM 加密小段敏感数据（如 AirPlay 长期身份种子）。
 * 密钥在硬件/TEE 支持的 AndroidKeyStore 内,不导出——root/adb backup 拿到密文也解不开。
 * 零第三方依赖（不引 androidx.security）。Keystore 不可用的罕见设备由调用方兜底明文。
 */
object KeystoreBox {
    private const val ALIAS = "airsonic_seed_key"
    private const val KS = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    /** 加密 → Base64(IV‖密文‖tag)。 */
    fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain)
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    /** 解密 Base64(IV‖密文‖tag)；失败抛异常由调用方处理。 */
    fun decrypt(b64: String): ByteArray {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_LEN))
        }
        return c.doFinal(all, IV_LEN, all.size - IV_LEN)
    }
}
