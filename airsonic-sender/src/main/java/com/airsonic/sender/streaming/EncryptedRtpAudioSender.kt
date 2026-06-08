package com.airsonic.sender.streaming

import com.airsonic.sender.pairing.CryptoPrimitives
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 加密 RTP 音频发送器（对齐真 AirPlay2 realtime 模式的包结构）。
 *
 * 真设备 realtime 包布局（取自参考接收端 ap2/connections/audio.py 的解析逻辑）：
 *   [12B RTP头][密文 payload][16B Poly1305 tag][8B nonce]
 *   - aad   = RTP头的 bytes[4:12]（timestamp + ssrc）
 *   - cipher= ChaCha20-Poly1305(key=audioKey, nonce, aad)
 *
 * 关键差异说明（务必回家用真设备 recon 校准）：
 *  1) audioKey 的来源：真设备的音频密钥来自 SETUP 阶段交换（而非直接用
 *     pair-verify 的 sharedKey）。本类用 [deriveAudioKey] 暂以 pair-verify
 *     会话密钥经 HKDF 派生，作为占位；真值需抓 SETUP 报文确认。
 *  2) nonce 长度：参考实现里 nonce 取 8B；BouncyCastle ChaCha20Poly1305 需 12B。
 *     本类统一用「8B 计数器左补 4 个 0 到 12B」，并在自测接收端用相同约定闭环；
 *     真设备的确切 nonce 约定需 recon 校准。
 *  3) 传输：真设备 realtime 走 UDP。本类支持 UDP（[sendOverUdp]）与
 *     TCP 交错（[sendOverTcpInterleaved]）两种，便于本地自测与真机切换。
 */
class EncryptedRtpAudioSender(
    private val audioKey: ByteArray,
    private val payloadType: Int = 96,
    private val ssrc: Int = 0x6A6F6E65,
    /** 非空时：每个 RTP 包负载先经 ALAC 未压缩编码再加密（贴近真设备）。 */
    private val alac: AlacEncoder? = null,
    private val channels: Int = 2,
    /** RTP 首包序号。真设备 RECORD 的 RTP-Info:seq 必须与首包一致（随机初值）。 */
    initialSeq: Int = 0,
    /** RTP 首包时间戳。RECORD 的 RTP-Info:rtptime 必须与首包一致（随机初值）。 */
    initialTimestamp: Int = 0,
    /**
     * 共享 AirPlay 时钟。非空时：RTP 包头 timestamp 由 [AirplayClock.rtptime32] 驱动，
     * 并在每包后推进时钟，使音频时间轴与 SyncPacket 锚点严格一致（真设备出声必需）。
     * 为空时：退化为内部自增 timestamp（本地 loopback 自测路径）。
     */
    private val clock: AirplayClock? = null
) {
    private var seq = initialSeq
    private var timestamp = initialTimestamp
    /** 首包标记：pyatv 首个音频包 type=0xE0（marker），其余 0x60。 */
    private var firstPacket = true
    /**
     * 加密 nonce 计数器（对齐 pyatv Chacha20Cipher._out_counter）：
     * 从 0 开始，每加密一个包 +1。nonce = counter 的 8 字节小端表示，左补 4 个 0 到 12B。
     * 包尾附带的就是这个 nonce 的后 8 字节（即小端 counter）。
     * 之前误用 timestamp+大端，导致 HomePod 解密失败、全丢、无声。
     */
    private var nonceCounter = 0L

    /**
     * 跨调用残留 PCM（关键修复，HomePod 静音根因之一）：
     * 解码器按变长块（如 32768B=8192帧）回调，若每块独立切包会在【每个块边界】
     * 产生一个不足 352 帧的残包，违反 SETUP#2 声明的 spf=352 → 设备缓冲期撞畸形包 → 整流被拒静音。
     * pyatv 从连续缓冲区严格每包读 352 帧，仅最后一包补零。此处缓存不足一包的残留，
     * 拼到下次，只发满包；[flushFinal] 补最后一包。
     */
    private var residual = ByteArray(0)
    /** 持久化 realtime 节流截止点（不再每个块重置，避免突发）。 */
    private var pacingDeadline = 0L
    private var framesPerPacketCfg = 352

    /** 首包序号（供 RECORD 的 RTP-Info 使用）。 */
    val startSeq: Int = initialSeq and 0xFFFF
    /** 首包时间戳（供 RECORD 的 RTP-Info 使用）。 */
    val startTimestamp: Int = initialTimestamp

    /** 构造一个加密 RTP 包（realtime 布局）。payload 已是最终负载（PCM 或 ALAC 帧）。 */
    fun buildPacket(payload: ByteArray, frameSamples: Int): ByteArray {
        // 时钟存在时用其 rtptime 作为本包 timestamp（与 SyncPacket 锚点一致）
        val ts = clock?.rtptime32 ?: timestamp
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        header.put(0x80.toByte())                       // proto: V=2
        // type: pyatv 首包=0xE0（0x80 marker | 0x60），后续=0x60。
        header.put((if (firstPacket) 0xE0 else 0x60).toByte())
        header.putShort((seq and 0xFFFF).toShort())
        header.putInt(ts)
        header.putInt(ssrc)
        val headerBytes = header.array()

        val aad = headerBytes.copyOfRange(4, 12)        // timestamp+ssrc
        // nonce 对齐 pyatv：counter 的 8 字节【小端】表示，左补 4 个 0 凑成 12B。
        // 包尾附带的 nonce8 = 该 12B 的后 8 字节（即小端 counter）。
        val nonce8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(nonceCounter).array()
        val nonce12 = ByteArray(12)
        System.arraycopy(nonce8, 0, nonce12, 4, 8)      // 左补 4 个 0
        nonceCounter++                                  // 每包 +1（对齐 pyatv out_counter）

        // BouncyCastle 输出 = 密文 || 16B tag
        val ctWithTag = CryptoPrimitives.chacha20Poly1305Encrypt(
            key = audioKey, nonce = nonce12, plaintext = payload, aad = aad
        )
        val ct = ctWithTag.copyOfRange(0, ctWithTag.size - 16)
        val tag = ctWithTag.copyOfRange(ctWithTag.size - 16, ctWithTag.size)

        // [header][ct][tag][nonce8]
        val out = ByteArray(12 + ct.size + 16 + 8)
        System.arraycopy(headerBytes, 0, out, 0, 12)
        System.arraycopy(ct, 0, out, 12, ct.size)
        System.arraycopy(tag, 0, out, 12 + ct.size, 16)
        System.arraycopy(nonce8, 0, out, 12 + ct.size + 16, 8)

        seq++
        firstPacket = false
        timestamp += frameSamples   // 每声道样本数推进时间戳（无时钟时用）
        clock?.advance(frameSamples) // 有时钟时推进共享时间轴（与 Sync 一致）
        return out
    }

    /** 把一段 framesPerPacket 的交错 PCM16 转成最终负载（ALAC 帧 or L16 大端 PCM）。 */
    private fun makePayload(pcm: ByteArray, offset: Int, len: Int): Pair<ByteArray, Int> {
        val frameSamples = len / (channels * 2)
        val slice = pcm.copyOfRange(offset, offset + len)
        return if (alac != null) {
            alac.encodeFrame(slice, frameSamples) to frameSamples
        } else {
            // PCM 路径：AirPlay L16 要求大端字节序（RFC 3551 / pyatv 一致）。
            // Android 解码器输出小端，这里逐样本交换高低字节。
            var i = 0
            while (i + 1 < slice.size) {
                val tmp = slice[i]
                slice[i] = slice[i + 1]
                slice[i + 1] = tmp
                i += 2
            }
            slice to frameSamples
        }
    }

    /**
     * UDP 发送整段 PCM（真设备 realtime 路径）。
     * @param sampleRate 用于 realtime 节流（按帧时长 sleep，避免一次性灌满接收端缓冲）。
     *                   传 0 关闭节流（本地自测/最快推送）。
     * @param onProgress 每发若干包回调一次累计字节，便于 UI 显示。
     */
    fun sendOverUdp(
        socket: DatagramSocket,
        target: InetAddress,
        port: Int,
        pcm: ByteArray,
        framesPerPacket: Int = 352,
        sampleRate: Int = 0,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ) {
        val packetBytes = framesPerPacket * channels * 2
        framesPerPacketCfg = framesPerPacket
        val perPacketNanos = if (sampleRate > 0)
            (framesPerPacket.toLong() * 1_000_000_000L) / sampleRate else 0L
        if (pacingDeadline == 0L) pacingDeadline = System.nanoTime()
        // 合并上次不足一包的残留，只发满包，尾部残留留到下次/flushFinal。
        val buf = if (residual.isEmpty()) pcm else residual + pcm
        var off = 0
        var sent = 0L
        while (buf.size - off >= packetBytes && !isCancelled()) {
            emitPacket(buf, off, packetBytes, socket, target, port)
            off += packetBytes
            sent += packetBytes
            onProgress(sent)
            if (perPacketNanos > 0) {
                pacingDeadline += perPacketNanos
                val sleepNs = pacingDeadline - System.nanoTime()
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt()) }
                    catch (_: InterruptedException) { residual = buf.copyOfRange(off, buf.size); return }
                }
            }
        }
        residual = buf.copyOfRange(off, buf.size)
    }

    /** 构造并发送一个满包（含首包诊断）。 */
    private fun emitPacket(
        src: ByteArray, offset: Int, len: Int,
        socket: DatagramSocket, target: InetAddress, port: Int
    ) {
        val (payload, n) = makePayload(src, offset, len)
        val pkt = buildPacket(payload, n)
        socket.send(DatagramPacket(pkt, pkt.size, target, port))
    }

    /**
     * 推流结束时调用：把残留 PCM 补零到满包发出（对齐 pyatv 仅末包补零）。
     * @return 发出的尾包数（0 或 1）。
     */
    fun flushFinal(socket: DatagramSocket, target: InetAddress, port: Int): Int {
        if (residual.isEmpty()) return 0
        val packetBytes = framesPerPacketCfg * channels * 2
        val padded = residual.copyOf(packetBytes)   // 末尾补 0
        emitPacket(padded, 0, packetBytes, socket, target, port)
        residual = ByteArray(0)
        return 1
    }

    /**
     * 文件放完后补静音 padding 包（对齐 pyatv `_send_packet`：发全 0 包直到 padding≥latency）。
     * 让接收端 Sync 锚点持续对齐、缓冲干净播完整段，避免提前 cut。realtime 节流续用同一截止点。
     * @param paddingFrames 需补的总帧数（每声道），通常 = AirplayClock.latency。
     * @return 发出的 padding 包数。
     */
    fun sendSilencePadding(
        socket: DatagramSocket, target: InetAddress, port: Int,
        paddingFrames: Int, framesPerPacket: Int = 352, sampleRate: Int = 0,
        isCancelled: () -> Boolean = { false }
    ): Int {
        val packetBytes = framesPerPacket * channels * 2
        val zero = ByteArray(packetBytes)
        val perPacketNanos = if (sampleRate > 0)
            (framesPerPacket.toLong() * 1_000_000_000L) / sampleRate else 0L
        if (pacingDeadline == 0L) pacingDeadline = System.nanoTime()
        var sentFrames = 0
        var pkts = 0
        while (sentFrames < paddingFrames && !isCancelled()) {
            emitPacket(zero, 0, packetBytes, socket, target, port)
            sentFrames += framesPerPacket
            pkts++
            if (perPacketNanos > 0) {
                pacingDeadline += perPacketNanos
                val sleepNs = pacingDeadline - System.nanoTime()
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt()) }
                    catch (_: InterruptedException) { return pkts }
                }
            }
        }
        return pkts
    }

    /** TCP 交错发送（本地自测路径，2B 长度前缀 + 加密包）。 */
    fun sendOverTcpInterleaved(out: OutputStream, pcm: ByteArray, framesPerPacket: Int = 352) {
        val chunk = framesPerPacket * channels * 2
        var off = 0
        while (off < pcm.size) {
            val len = minOf(chunk, pcm.size - off)
            val (payload, n) = makePayload(pcm, off, len)
            val pkt = buildPacket(payload, n)
            out.write((pkt.size ushr 8) and 0xFF)
            out.write(pkt.size and 0xFF)
            out.write(pkt)
            off += len
        }
    }

    fun finishTcp(out: OutputStream) {
        out.write(0); out.write(0); out.flush()
    }

    companion object {
        /**
         * 从 pair-verify 会话密钥派生音频密钥（占位实现）。
         * 真设备需以 SETUP 协商的 key 替换 —— 见类注释 (1)。
         */
        fun deriveAudioKey(pairVerifySessionKey: ByteArray): ByteArray =
            CryptoPrimitives.hkdfSha512(
                ikm = pairVerifySessionKey,
                salt = "AirSonic-Audio-Salt".toByteArray(),
                info = "AirSonic-Audio-Key".toByteArray(),
                length = 32
            )
    }
}
