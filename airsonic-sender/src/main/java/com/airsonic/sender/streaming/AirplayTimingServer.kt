// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * AirPlay 2 / RAOP 计时服务器（严格对齐 pyatv TimingServer，见
 * pyatv/protocols/raop/protocols/__init__.py 与 packets.py TimingPacket）。
 *
 * 设备会向我方 SETUP#1 上报的 timingPort 发送 32B TimingPacket 请求，
 * 我方必须回包让设备完成时钟同步。少了它，真设备无法对齐播放时钟 → 静音。
 *
 * TimingPacket 布局（全 big-endian，共 32B）：
 *   proto(1) type(1) seqno(2) padding(4)
 *   reftime_sec(4) reftime_frac(4)
 *   recvtime_sec(4) recvtime_frac(4)
 *   sendtime_sec(4) sendtime_frac(4)
 *
 * 响应（对齐 pyatv）：
 *   type = 0x53 | 0x80 = 0xD3, seqno = 7, padding = 0,
 *   reftime = 请求的 sendtime, recvtime = 收到时的 NTP, sendtime = 发出时的 NTP。
 */
class AirplayTimingServer {
    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    /** 实际绑定的本地端口（传 0 由系统分配）。SETUP#1 的 timingPort 用它。 */
    val port: Int
        get() = socket?.localPort ?: 0

    /** 启动 UDP 监听线程。返回绑定端口。onEvent 用于把收发事件上报到 UI。 */
    fun start(bindPort: Int = 0, onEvent: ((String) -> Unit)? = null): Int {
        val s = DatagramSocket(bindPort)
        socket = s
        running = true
        worker = thread(name = "airplay-timing", isDaemon = true) {
            val buf = ByteArray(64)
            var rxCount = 0
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    if (pkt.length < 32) continue
                    rxCount++
                    Log.i(TAG, "RX timing request from ${pkt.address.hostAddress}:${pkt.port} len=${pkt.length}")
                    onEvent?.invoke("⏱ timing 收到设备请求 #$rxCount from ${pkt.address.hostAddress}")
                    val resp = buildResponse(pkt.data, pkt.length)
                    s.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                    Log.i(TAG, "TX timing response to ${pkt.address.hostAddress}:${pkt.port}")
                } catch (t: Throwable) {
                    if (running) Log.d(TAG, "timing loop: ${t.message}")
                }
            }
        }
        Log.d(TAG, "timing server on port ${s.localPort}")
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun buildResponse(req: ByteArray, len: Int): ByteArray {
        val bb = ByteBuffer.wrap(req, 0, len).order(ByteOrder.BIG_ENDIAN)
        val proto = bb.get()                 // [0] proto
        bb.get()                             // [1] type (忽略)
        bb.short                             // [2..3] seqno (忽略)
        bb.int                               // [4..7] padding
        bb.int; bb.int                       // [8..15] reftime (忽略)
        bb.int; bb.int                       // [16..23] recvtime (忽略)
        val sendSec = bb.int.toLong() and 0xFFFFFFFFL    // [24..27] sendtime_sec
        val sendFrac = bb.int.toLong() and 0xFFFFFFFFL   // [28..31] sendtime_frac

        val now = AirplayClock.ntpNow()
        val (nowSec, nowFrac) = AirplayClock.ntp2parts(now)

        val out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        out.put(proto)                       // proto 原样
        out.put(0xD3.toByte())               // type = 0x53|0x80
        out.putShort(7)                      // seqno = 7
        out.putInt(0)                        // padding = 0
        out.putInt(sendSec.toInt())          // reftime = 请求的 sendtime
        out.putInt(sendFrac.toInt())
        out.putInt(nowSec.toInt())           // recvtime = now
        out.putInt(nowFrac.toInt())
        out.putInt(nowSec.toInt())           // sendtime = now
        out.putInt(nowFrac.toInt())
        return out.array()
    }

    companion object {
        private const val TAG = "AirplayTimingServer"
    }
}