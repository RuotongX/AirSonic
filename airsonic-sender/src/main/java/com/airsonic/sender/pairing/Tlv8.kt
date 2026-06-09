// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.pairing

import java.io.ByteArrayOutputStream

/**
 * TLV8 编解码（HomeKit / AirPlay2 配对所用的类型-长度-值格式）。
 *
 * 规则：每项为 1 字节 type + 1 字节 length + value；当某个 type 的 value
 * 超过 255 字节时，需拆成多段连续相同 type 的分片，读取时再拼接。
 */
object Tlv8 {

    /** 常用 TLV 类型（HAP 规范子集）。 */
    object Type {
        const val METHOD: Int = 0x00
        const val IDENTIFIER: Int = 0x01
        const val SALT: Int = 0x02
        const val PUBLIC_KEY: Int = 0x03
        const val PROOF: Int = 0x04
        const val ENCRYPTED_DATA: Int = 0x05
        const val STATE: Int = 0x06
        const val ERROR: Int = 0x07
        const val SIGNATURE: Int = 0x0A
        const val FLAGS: Int = 0x13  // kTLVType_Flags：transient/split 配对标志（4B 大端）
    }

    fun encode(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in entries) {
            if (value.isEmpty()) {
                out.write(type)
                out.write(0)
                continue
            }
            var offset = 0
            while (offset < value.size) {
                val chunk = minOf(255, value.size - offset)
                out.write(type)
                out.write(chunk)
                out.write(value, offset, chunk)
                offset += chunk
            }
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArrayOutputStream>()
        var i = 0
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + len > data.size) break
            val bucket = result.getOrPut(type) { ByteArrayOutputStream() }
            bucket.write(data, i, len)
            i += len
        }
        return result.mapValues { it.value.toByteArray() }
    }
}