// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简 Apple binary-plist (bplist00) 编/解码器。
 *
 * AirPlay2 的 SETUP/RECORD 等请求体是 binary plist；Android 无内置 plist 库，故自研。
 * 仅覆盖 AirPlay 握手所需类型：Dict / Array / String(ASCII/UTF8) / Int / Bool / Data。
 *
 * 已对齐 Python plistlib FMT_BINARY 的输出布局（objects → offset table → trailer）。
 */
object BPlist {

    // ---- 编码 ----

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        flatten(root, objects)
        val count = objects.size
        val refSize = byteWidth(count)

        // 先编码各对象体（引用用 refSize 宽度），记录偏移
        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = LongArray(count)
        for (i in 0 until count) {
            offsets[i] = body.size().toLong()
            encodeObject(objects[i], objects, refSize, body)
        }

        // offset table
        val offsetTableStart = body.size().toLong()
        val offsetSize = byteWidth(body.size())
        for (off in offsets) writeSizedInt(body, off, offsetSize)

        // trailer (32B)
        val tr = ByteArray(32)
        tr[6] = offsetSize.toByte()
        tr[7] = refSize.toByte()
        writeLongBE(tr, 8, count.toLong())
        writeLongBE(tr, 16, 0L) // top object index
        writeLongBE(tr, 24, offsetTableStart)
        body.write(tr)
        return body.toByteArray()
    }

    /** 深度优先收集所有对象，保证子对象在父之后但有稳定索引（与 plistlib 一致：广度收集亦可，这里用唯一化）。 */
    private fun flatten(root: Any?, out: ArrayList<Any?>) {
        // 使用队列做层序，确保 ref 索引稳定
        val queue = ArrayDeque<Any?>()
        queue.add(root)
        out.add(root)
        val index = HashMap<IdentityKey, Int>()
        index[IdentityKey(root)] = 0
        while (queue.isNotEmpty()) {
            when (val cur = queue.removeFirst()) {
                is Map<*, *> -> {
                    for ((k, v) in cur) {
                        addUnique(k, out, index, queue)
                    }
                    for ((_, v) in cur) {
                        addUnique(v, out, index, queue)
                    }
                }
                is List<*> -> for (v in cur) addUnique(v, out, index, queue)
            }
        }
    }

    private fun addUnique(
        obj: Any?,
        out: ArrayList<Any?>,
        index: HashMap<IdentityKey, Int>,
        queue: ArrayDeque<Any?>
    ) {
        val key = IdentityKey(obj)
        if (index.containsKey(key)) return
        index[key] = out.size
        out.add(obj)
        queue.add(obj)
    }

    private class IdentityKey(val v: Any?) {
        override fun hashCode() = when (v) {
            null -> 0
            is String, is Int, is Long, is Boolean, is Double, is Float -> v.hashCode()
            else -> System.identityHashCode(v)
        }
        override fun equals(other: Any?): Boolean {
            if (other !is IdentityKey) return false
            return when (v) {
                null -> other.v == null
                is String, is Int, is Long, is Boolean, is Double, is Float -> v == other.v
                else -> v === other.v
            }
        }
    }

    private fun refIndex(obj: Any?, objects: List<Any?>): Int {
        // 与 flatten 的去重策略一致
        for (i in objects.indices) {
            val o = objects[i]
            val match = when (obj) {
                null -> o == null
                is String, is Int, is Long, is Boolean, is Double, is Float -> obj == o
                else -> obj === o
            }
            if (match) return i
        }
        return 0
    }

    private fun encodeObject(obj: Any?, objects: List<Any?>, refSize: Int, out: ByteArrayOutputStream) {
        when (obj) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (obj) 0x09 else 0x08)
            is Int -> encodeInt(obj.toLong(), out)
            is Long -> encodeInt(obj, out)
            is Float -> encodeReal(obj.toDouble(), bits32 = true, out)
            is Double -> encodeReal(obj, bits32 = false, out)
            is String -> encodeString(obj, out)
            is ByteArray -> encodeData(obj, out)
            is Map<*, *> -> {
                writeCountMarker(0xD0, obj.size, out)
                for (k in obj.keys) writeSizedInt(out, refIndex(k, objects).toLong(), refSize)
                for (v in obj.values) writeSizedInt(out, refIndex(v, objects).toLong(), refSize)
            }
            is List<*> -> {
                writeCountMarker(0xA0, obj.size, out)
                for (v in obj) writeSizedInt(out, refIndex(v, objects).toLong(), refSize)
            }
            else -> error("BPlist 不支持的类型: ${obj::class}")
        }
    }

    /** 编码 real（0x22=float32, 0x23=double64），大端字节序（bplist00 规范）。 */
    private fun encodeReal(v: Double, bits32: Boolean, out: ByteArrayOutputStream) {
        if (bits32) {
            out.write(0x22)
            val bits = java.lang.Float.floatToIntBits(v.toFloat())
            val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bits).array()
            out.write(buf)
        } else {
            out.write(0x23)
            val bits = java.lang.Double.doubleToLongBits(v)
            val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(bits).array()
            out.write(buf)
        }
    }

    private fun encodeInt(v: Long, out: ByteArrayOutputStream) {
        when {
            v in 0..0xFF -> { out.write(0x10); out.write(v.toInt() and 0xFF) }
            v in 0..0xFFFF -> { out.write(0x11); writeSizedInt(out, v, 2) }
            v in 0..0xFFFFFFFFL -> { out.write(0x12); writeSizedInt(out, v, 4) }
            else -> { out.write(0x13); writeSizedInt(out, v, 8) }
        }
    }

    private fun encodeString(s: String, out: ByteArrayOutputStream) {
        val isAscii = s.all { it.code < 0x80 }
        if (isAscii) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            writeCountMarker(0x50, bytes.size, out)
            out.write(bytes)
        } else {
            val chars = s.toCharArray()
            writeCountMarker(0x60, chars.size, out)
            for (c in chars) { out.write((c.code shr 8) and 0xFF); out.write(c.code and 0xFF) }
        }
    }

    private fun encodeData(d: ByteArray, out: ByteArrayOutputStream) {
        writeCountMarker(0x40, d.size, out)
        out.write(d)
    }

    private fun writeCountMarker(typeNibble: Int, count: Int, out: ByteArrayOutputStream) {
        if (count < 15) {
            out.write(typeNibble or count)
        } else {
            out.write(typeNibble or 0x0F)
            encodeInt(count.toLong(), out)
        }
    }

    private fun writeSizedInt(out: ByteArrayOutputStream, v: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    private fun writeLongBE(buf: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) buf[off + i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()
    }

    private fun byteWidth(maxValue: Int): Int = when {
        maxValue < 0x100 -> 1
        maxValue < 0x10000 -> 2
        maxValue < 0x1000000 -> 3
        else -> 4
    }

    // ---- 解码（仅 AirPlay 响应所需）----

    fun decode(data: ByteArray): Any? {
        require(data.size >= 40 && String(data, 0, 8, Charsets.US_ASCII) == "bplist00") {
            "非 bplist00"
        }
        val trailer = data.copyOfRange(data.size - 32, data.size)
        val offsetSize = trailer[6].toInt() and 0xFF
        val refSize = trailer[7].toInt() and 0xFF
        val numObjects = readLongBE(trailer, 8).toInt()
        val topIndex = readLongBE(trailer, 16).toInt()
        val offsetTableStart = readLongBE(trailer, 24).toInt()

        val offsets = IntArray(numObjects)
        for (i in 0 until numObjects) {
            offsets[i] = readSizedInt(data, offsetTableStart + i * offsetSize, offsetSize).toInt()
        }
        val ctx = DecodeCtx(data, offsets, refSize)
        return ctx.readObject(topIndex)
    }

    private class DecodeCtx(val data: ByteArray, val offsets: IntArray, val refSize: Int) {
        fun readObject(index: Int): Any? {
            var pos = offsets[index]
            val marker = data[pos].toInt() and 0xFF
            val type = marker and 0xF0
            val nib = marker and 0x0F
            pos++
            return when (type) {
                0x00 -> when (nib) { 0x08 -> false; 0x09 -> true; else -> null }
                0x10 -> { val n = 1 shl nib; readSizedInt(data, pos, n) }
                0x20 -> when (nib) {
                    2 -> {
                        val bits = ((data[pos].toInt() and 0xFF) shl 24) or
                            ((data[pos+1].toInt() and 0xFF) shl 16) or
                            ((data[pos+2].toInt() and 0xFF) shl 8) or
                            (data[pos+3].toInt() and 0xFF)
                        java.lang.Float.intBitsToFloat(bits).toDouble()
                    }
                    3 -> {
                        var bits = 0L
                        for (i in 0 until 8) bits = (bits shl 8) or (data[pos + i].toLong() and 0xFF)
                        java.lang.Double.longBitsToDouble(bits)
                    }
                    else -> null
                }
                0x40 -> { val (len, p) = readCount(nib, pos); data.copyOfRange(p, p + len) }
                0x50 -> { val (len, p) = readCount(nib, pos); String(data, p, len, Charsets.US_ASCII) }
                0x60 -> {
                    val (len, p) = readCount(nib, pos)
                    val sb = StringBuilder()
                    for (i in 0 until len) {
                        val c = ((data[p + i * 2].toInt() and 0xFF) shl 8) or (data[p + i * 2 + 1].toInt() and 0xFF)
                        sb.append(c.toChar())
                    }
                    sb.toString()
                }
                0xA0 -> {
                    val (len, p) = readCount(nib, pos)
                    val list = ArrayList<Any?>(len)
                    for (i in 0 until len) {
                        val ref = readSizedInt(data, p + i * refSize, refSize).toInt()
                        list.add(readObject(ref))
                    }
                    list
                }
                0xD0 -> {
                    val (len, p) = readCount(nib, pos)
                    val map = LinkedHashMap<Any?, Any?>()
                    val keys = IntArray(len) { readSizedInt(data, p + it * refSize, refSize).toInt() }
                    val valsStart = p + len * refSize
                    val vals = IntArray(len) { readSizedInt(data, valsStart + it * refSize, refSize).toInt() }
                    for (i in 0 until len) map[readObject(keys[i])] = readObject(vals[i])
                    map
                }
                else -> null
            }
        }

        private fun readCount(nib: Int, pos: Int): Pair<Int, Int> {
            if (nib != 0x0F) return nib to pos
            val intMarker = data[pos].toInt() and 0xFF
            val n = 1 shl (intMarker and 0x0F)
            val count = readSizedInt(data, pos + 1, n).toInt()
            return count to (pos + 1 + n)
        }
    }

    private fun readSizedInt(data: ByteArray, off: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (data[off + i].toLong() and 0xFF)
        return v
    }

    private fun readLongBE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }
}