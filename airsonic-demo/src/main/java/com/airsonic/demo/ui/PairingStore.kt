// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.content.Context
import android.util.Base64

/**
 * 持久化 AirPlay 配对身份：
 * - 全局 ltSeed(32B Ed25519 种子，首次生成后固定)：pair-setup 后接收端记住我方 LTPK，
 *   pair-verify 必须复用同一 ltSeed 才能被认出。
 * - paired：已完成 PIN 配对的设备 host 集合 → 下次直接 pair-verify。
 */
object PairingStore {
    private const val PREF = "airsonic_pairing"
    private const val KEY_SEED = "lt_seed"
    private const val KEY_PAIRED = "paired_hosts"
    private const val KEY_PAIRID = "pairing_id"

    /** 取（或首次生成并存）配对标识 ——必须是合法 UUID（Apple TV 校验格式，否则 M5 拒）。 */
    fun pairingId(context: Context): String {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.getString(KEY_PAIRID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        sp.edit().putString(KEY_PAIRID, id).apply()
        return id
    }

    /** 取（或首次生成并存）全局 32B ltSeed。 */
    fun ltSeed(context: Context): ByteArray {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.getString(KEY_SEED, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val seed = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        sp.edit().putString(KEY_SEED, Base64.encodeToString(seed, Base64.NO_WRAP)).apply()
        return seed
    }

    fun isPaired(context: Context, host: String): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_PAIRED, emptySet())?.contains(host) == true

    fun markPaired(context: Context, host: String) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val set = HashSet(sp.getStringSet(KEY_PAIRED, emptySet()) ?: emptySet())
        set.add(host)
        sp.edit().putStringSet(KEY_PAIRED, set).apply()
    }

    fun unpair(context: Context, host: String) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val set = HashSet(sp.getStringSet(KEY_PAIRED, emptySet()) ?: emptySet())
        set.remove(host)
        sp.edit().putStringSet(KEY_PAIRED, set).remove("$KEY_ACC_LTPK$host").apply()
    }

    /** 首次 PIN 配对(M6 已验签)得到的接收端 LTPK；pair-verify 用它强制验签防冒充。 */
    fun accessoryLtpk(context: Context, host: String): ByteArray? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("$KEY_ACC_LTPK$host", null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun saveAccessoryLtpk(context: Context, host: String, ltpk: ByteArray) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("$KEY_ACC_LTPK$host", Base64.encodeToString(ltpk, Base64.NO_WRAP))
            .apply()
    }

    private const val KEY_ACC_LTPK = "acc_ltpk_"
}