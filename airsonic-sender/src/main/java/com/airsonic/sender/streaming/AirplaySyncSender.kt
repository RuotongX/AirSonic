package com.airsonic.sender.streaming

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * AirPlay 2 / RAOP 同步包发送器（严格对齐 pyatv ControlClient._sync_task，见
 * pyatv/protocols/raop/stream_client.py 与 packets.py SyncPacket）。
 *
 * 这是「出声」最关键的一环：每 1 秒向设备的 controlPort 发送一个 SyncPacket，
 * 把 NTP 时钟与 RTP 时间戳锚定起来。接收端据此设置 anchorRTPTimestamp，
 * 才能算出每个 RTP 包的播放时刻。少了它 → 接收端不知道何时播放 → 静音。
 *
 * SyncPacket 布局（全 big-endian，共 20B）：
 *   proto(1)  type(1)  seqno(2)
 *   now_without_latency(4)   # rtptime - latency
 *   last_sync_sec(4)  last_sync_frac(4)   # head_ts 对应的 NTP
 *   now(4)                   # rtptime
 *
 * 对齐 pyatv：
 *   proto = 0x90（首包）/ 0x80（之后），type = 0xD4，seqno = 0x0007，
 *   间隔 1.0s，发送到 (deviceIp, deviceControlPort)。
 */
class AirplaySyncSender(
    private val clock: AirplayClock,
    private val deviceIp: String,
    private val deviceControlPort: Int,
    /** 复用音频 socket 之外的独立 socket（绑定到我方 controlPort 更佳，可为 0）。 */
    private val localBindPort: Int = 0,
    /**
     * 复用外部已绑定端口的控制 socket（对齐 pyatv）：sync 从此 socket 发出（源端口=声明的 controlPort），
     * 并在此 socket 上收设备控制/retransmit 消息。非空时不自建、不在 stop 关闭。
     */
    private val externalSocket: DatagramSocket? = null
) {
    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var rxWorker: Thread? = null

    val localPort: Int get() = socket?.localPort ?: 0

    fun start() {
        val s = externalSocket ?: DatagramSocket(localBindPort)
        socket = s
        running = true
        val target = InetAddress.getByName(deviceIp)
        // 接收线程：吸收设备发到我方 controlPort 的消息（retransmit/控制），并诊断上报。
        rxWorker = thread(name = "airplay-control-rx", isDaemon = true) {
            val buf = ByteArray(2048)
            var rx = 0
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    rx++
                    val t = if (p.length >= 2) (p.data[1].toInt() and 0x7F) else -1
                    Log.i(TAG, "RX control msg #$rx len=${p.length} type=0x${Integer.toHexString(t)} from ${p.address.hostAddress}")
                } catch (ie: InterruptedException) {
                    return@thread
                } catch (t: Throwable) {
                    if (running) Log.d(TAG, "control rx: ${t.message}")
                    else return@thread
                }
            }
        }
        worker = thread(name = "airplay-sync", isDaemon = true) {
            var firstPacket = true
            while (running) {
                try {
                    val pkt = buildSyncPacket(firstPacket)
                    s.send(DatagramPacket(pkt, pkt.size, target, deviceControlPort))
                    Log.i(TAG, "TX sync rtptime=${clock.rtptime} headTs=${clock.headTs} → $deviceIp:$deviceControlPort")
                    firstPacket = false
                    Thread.sleep(1000L)
                } catch (ie: InterruptedException) {
                    return@thread
                } catch (t: Throwable) {
                    if (running) Log.d(TAG, "sync loop: ${t.message}")
                }
            }
        }
        Log.d(TAG, "sync sender → $deviceIp:$deviceControlPort (local ${s.localPort})")
    }

    fun stop() {
        running = false
        worker?.interrupt()
        rxWorker?.interrupt()
        // 外部 socket 由创建方负责关闭，这里只关自建的
        if (externalSocket == null) runCatching { socket?.close() }
        socket = null
    }

    private fun buildSyncPacket(firstPacket: Boolean): ByteArray {
        val rtptime = clock.rtptime and 0xFFFFFFFFL
        val nowWithoutLatency = (clock.rtptime - clock.latency) and 0xFFFFFFFFL
        val (lastSec, lastFrac) = AirplayClock.ntp2parts(clock.syncNtp())

        val bb = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        bb.put(if (firstPacket) 0x90.toByte() else 0x80.toByte())  // proto
        bb.put(0xD4.toByte())                                      // type
        bb.putShort(0x0007)                                        // seqno
        bb.putInt(nowWithoutLatency.toInt())                       // now_without_latency
        bb.putInt(lastSec.toInt())                                 // last_sync_sec
        bb.putInt(lastFrac.toInt())                                // last_sync_frac
        bb.putInt(rtptime.toInt())                                 // now
        return bb.array()
    }

    companion object {
        private const val TAG = "AirplaySyncSender"
    }
}
