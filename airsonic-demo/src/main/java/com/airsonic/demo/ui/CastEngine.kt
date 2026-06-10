// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.airsonic.demo.capture.CaptureProjectionService
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.api.DeviceType
import com.airsonic.sender.capture.SystemAudioCapture
import com.airsonic.sender.discovery.AirplayDiscovery
import com.airsonic.sender.discovery.DlnaDiscovery
import com.airsonic.sender.dlna.DlnaController
import com.airsonic.sender.dlna.buildDidl
import com.airsonic.sender.pairing.PairingHandshake
import com.airsonic.sender.streaming.AirplayStreamSession
import com.airsonic.sender.streaming.BPlist
import kotlin.concurrent.thread

/** 投送阶段。 */
enum class CastPhase { IDLE, CONNECTING, CASTING, ERROR }

/**
 * 投送大脑：设备发现 + 选中设备 + 投送状态/动作。对 Compose 暴露快照状态。
 * 复用 airsonic-sender 后端（发现/配对/SETUP/捕获/推流）。
 */
object CastEngine {
    // ---- 设备发现 ----
    val devices: SnapshotStateList<AirDevice> = mutableStateListOf()
    var selected = mutableStateOf<AirDevice?>(null)
        private set
    var discovering = mutableStateOf(false)
        private set

    // ---- 投送状态 ----
    val phase = mutableStateOf(CastPhase.IDLE)
    val statusLine = mutableStateOf("")
    /** 实时音频幅度 0..1，驱动律动动图。 */
    val level = mutableStateOf(0f)
    /** 投送开始时刻（SystemClock.elapsedRealtime）；0=未投。用于时长计时。 */
    val startedAt = mutableStateOf(0L)

    /** 强制使用 ALAC 编码（Sonos 等只收 ALAC 的设备调试用；持久化）。HomePod 走自动探测不受影响。 */
    val forceAlac = mutableStateOf(false)
    /** 当前会话实际使用的音频编码标签（"ALAC"/"PCM"），供 UI 调试显示。 */
    val activeCodec = mutableStateOf("")

    private const val PREFS = "airsonic_prefs"

    fun loadPrefs(context: Context) {
        forceAlac.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("force_alac", false)
    }

    fun setForceAlac(context: Context, v: Boolean) {
        forceAlac.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("force_alac", v).apply()
    }

    private var discovery: AirplayDiscovery? = null
    @Volatile private var casting = false
    private var capture: SystemAudioCapture? = null
    private var worker: Thread? = null
    private var savedVolume: Int = -1
    private var httpServer: com.airsonic.sender.streaming.LocalMediaHttpServer? = null
    private var videoCtl: com.airsonic.sender.streaming.AirplayVideoController? = null
    private var dlnaDiscovery: DlnaDiscovery? = null
    @Volatile private var dlnaCtl: DlnaController? = null
    val isVideo = mutableStateOf(false)
    val videoPos = mutableStateOf(0.0)
    val videoDur = mutableStateOf(0.0)

    // ---- PIN 配对 ----
    val pinRequest = mutableStateOf<String?>(null)
    /** 每次弹 PIN 框自增，驱动输入框清空（防残留旧值被误交）。 */
    val pinNonce = mutableStateOf(0)
    private val pinQueue = java.util.concurrent.ArrayBlockingQueue<String>(1)
    private val PIN_CANCEL = " CANCEL"
    @Volatile private var pinAborted = false

    fun supportsVideo(d: AirDevice): Boolean = d.type == DeviceType.DLNA || d.capabilities.supportsVideo

    /** 凡 `_airplay._tcp` 发现的设备均可试投（实测 HomePod/Sonos/小米 均走标准 transient 配对）。 */
    fun isCastable(d: AirDevice): Boolean = true

    fun typeLabel(d: AirDevice): String = when (d.type) {
        DeviceType.HOMEPOD -> "HomePod"
        DeviceType.APPLE_TV -> "Apple TV"
        DeviceType.MAC -> "Mac"
        DeviceType.SONOS -> "Sonos"
        DeviceType.XIAOMI -> if (L10n.lang.value == Lang.EN) "Xiaomi Speaker" else "小米音箱"
        DeviceType.DLNA -> L10n.s.dlnaDevice
        DeviceType.UNKNOWN -> if (L10n.lang.value == Lang.EN) "AirPlay device" else "AirPlay 设备"
    }

    /**
     * 启动或刷新设备发现。可重复调用（进页面 + 下拉刷新都走这里）。
     *
     * 刷新时**不销毁重建** AirPlay 发现，而是复用同一 [AirplayDiscovery] 实例由其内部做
     * 「干净重启」——这修掉了华为 EMUI 上「刷新后设备全部消失再也回不来」的竞态
     * （vivo 拆/建快所以一直正常）。
     */
    fun startDiscovery(context: Context) {
        val app = context.applicationContext
        // 首次启动清空列表；刷新时保留现有设备，靠 onServiceFound/Lost 就地合并/剔除，
        // 避免重启发现期间出现「列表先空、设备再慢慢回填」的空窗。
        if (discovery == null) devices.clear()
        discovering.value = true

        // AirPlay：复用单一实例；start() 内部对「已在发现中」会发起干净重启。
        val ap = discovery ?: AirplayDiscovery(app).also { discovery = it }
        ap.start(object : DeviceListener {
            override fun onDeviceFound(device: AirDevice) {
                val idx = devices.indexOfFirst { it.id == device.id }
                if (idx >= 0) devices[idx] = device else devices.add(device)
                // 自动选中第一台可投设备（若尚未选）
                if (selected.value == null && isCastable(device)) selected.value = device
                // 注：Sonos 识别/路由改为「投送时即时探测 :1400」（见 startSystemAudioCast），
                // 发现期不再起探测线程，避免给本就脆弱的 NsdManager 发现添乱。
            }
            override fun onDeviceLost(device: AirDevice) {
                devices.removeAll { it.id == device.id }
            }
            override fun onDiscoveryFailed(reason: String) {
                statusLine.value = "${L10n.s.discoverFail}$reason"
            }
        })

        // DLNA：SSDP 为一次性 M-SEARCH，重建并重新搜索是安全的（同步关闭，无 NsdManager 那种异步竞态）。
        runCatching { dlnaDiscovery?.stop() }
        DlnaDiscovery(app).also { dl ->
            dlnaDiscovery = dl
            dl.start(object : DeviceListener {
                override fun onDeviceFound(device: AirDevice) {
                    val idx = devices.indexOfFirst { it.id == device.id }
                    if (idx >= 0) devices[idx] = device else devices.add(device)
                    if (selected.value == null && isCastable(device)) selected.value = device
                }
                override fun onDeviceLost(device: AirDevice) { devices.removeAll { it.id == device.id } }
                override fun onDiscoveryFailed(reason: String) { /* DLNA 发现失败不打断 AirPlay */ }
            })
        }
    }

    fun stopDiscovery() {
        runCatching { discovery?.stop() }
        discovery = null
        runCatching { dlnaDiscovery?.stop() }
        dlnaDiscovery = null
        discovering.value = false
    }

    fun select(d: AirDevice) { selected.value = d }

    /** 系统音频捕获投送（屏幕镜像仅声音 / 投应用 / 浏览器）。需 MediaProjection 授权结果。 */
    fun startSystemAudioCast(context: Context, resultCode: Int, data: Intent) {
        val sel = selected.value ?: run { statusLine.value = L10n.s.noDevice; phase.value = CastPhase.ERROR; return }
        // 取列表里最新的同 id 条目（可能已被异步探测升级为 SONOS），避免用到旧引用误走 AirPlay
        val device = devices.firstOrNull { it.id == sel.id } ?: sel
        if (!isCastable(device)) { statusLine.value = "${device.name} ${L10n.s.notSupportedSuffix}"; phase.value = CastPhase.ERROR; return }
        val app = context.applicationContext
        phase.value = CastPhase.CONNECTING
        statusLine.value = "${L10n.s.connecting} ${device.name} …"
        casting = true
        CaptureProjectionService.start(app)
        worker = thread(name = "airsonic-cast", isDaemon = true) {
            var cap: SystemAudioCapture? = null
            try {
                var waited = 0
                while (!CaptureProjectionService.isForeground && waited < 2000) { Thread.sleep(50); waited += 50 }
                val pm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = pm.getMediaProjection(resultCode, data)
                val cc = SystemAudioCapture(); cap = cc
                if (!cc.start(projection)) { fail(L10n.s.captureFail); return@thread }
                capture = cc
                // Sonos：走 UPnP 实时 AAC 流（不进 AirPlay）。
                // 发现阶段的 :1400 探测时好时坏（浏览器证 :1400 可达且快），故在投送时对「未知类型」
                // 设备多探几次把它探可靠；已识别的 AirPlay 设备（HomePod/AppleTV/Mac/小米）跳过，不增延迟。
                val sonosCtl: String? = when {
                    device.type == DeviceType.SONOS && device.controlUrl != null -> device.controlUrl
                    device.type == DeviceType.SONOS || device.type == DeviceType.UNKNOWN -> {
                        var c: String? = null
                        repeat(4) {
                            if (c == null && device.host.isNotEmpty()) {
                                c = com.airsonic.sender.dlna.probeSonos(device.host, connectTimeoutMs = 3000, readTimeoutMs = 3000)
                                if (c == null) Thread.sleep(500)
                            }
                        }
                        c
                    }
                    else -> null
                }
                if (sonosCtl != null) {
                    startSonosAudioStream(app, cc, device.copy(type = DeviceType.SONOS, controlUrl = sonosCtl))
                    return@thread
                }
                val (session, result) = connect(app, device)
                    ?: run { fail(L10n.s.setupFail); return@thread }
                mutePhone(app)
                onCastingStarted(device.name)
                session.streamCapturedPcm(
                    result = result, channels = 2,
                    isCancelled = { !casting },
                    nextChunk = {
                        val c = cc.readChunk(4096)
                        if (c != null && c.size >= 64) level.value = peakOf(c)
                        c
                    }
                ) {}
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}")
            } finally {
                cleanup(app, cap)
            }
        }
    }

    /** 本地媒体（音频文件 / 视频文件音轨）投送。 */
    fun startFileCast(context: Context, uri: Uri) {
        val device = selected.value ?: run { statusLine.value = L10n.s.noDevice; phase.value = CastPhase.ERROR; return }
        if (device.type == DeviceType.DLNA) { startDlnaCast(context, uri, device, isVideoFile = false); return }
        if (!isCastable(device)) { statusLine.value = "${device.name} ${L10n.s.notSupportedSuffix}"; phase.value = CastPhase.ERROR; return }
        val app = context.applicationContext
        phase.value = CastPhase.CONNECTING
        statusLine.value = "${L10n.s.connecting} ${device.name} …"
        casting = true
        worker = thread(name = "airsonic-filecast", isDaemon = true) {
            var pfd: ParcelFileDescriptor? = null
            try {
                val (session, result) = connect(app, device)
                    ?: run { fail(L10n.s.setupFail); return@thread }
                pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: run { fail(L10n.s.openFail); return@thread }
                mutePhone(app)
                onCastingStarted(device.name)
                session.streamAudio(result, pfd.fileDescriptor, realtimePacing = true, isCancelled = { !casting }) {}
                if (casting) statusLine.value = L10n.s.playFinished
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}")
            } finally {
                runCatching { pfd?.close() }
                cleanup(app, null)
            }
        }
    }

    /** 投视频：TV → /play 全屏播放；音箱 → 回退只投音轨。 */
    fun startVideoCast(context: Context, uri: Uri) {
        val device = selected.value ?: run { statusLine.value = L10n.s.noDevice; phase.value = CastPhase.ERROR; return }
        if (device.type == DeviceType.DLNA) { startDlnaCast(context, uri, device, isVideoFile = true); return }
        if (!supportsVideo(device)) { startFileCast(context, uri); return }
        val app = context.applicationContext
        phase.value = CastPhase.CONNECTING
        statusLine.value = "${L10n.s.connecting} ${device.name} …"
        casting = true; isVideo.value = true
        worker = thread(name = "airsonic-video", isDaemon = true) {
            try {
                val src = ContentResolverRangeSource(app, uri)
                if (src.length <= 0) { fail(L10n.s.openFail); return@thread }
                val server = com.airsonic.sender.streaming.LocalMediaHttpServer(src)
                val port = server.start(); httpServer = server
                val localIp = localWifiIp() ?: run { fail("${L10n.s.castError}no ip"); return@thread }
                val url = "http://$localIp:$port${server.path}"
                val hs = pairFor(app, device) ?: run { if (phase.value != CastPhase.ERROR) fail(L10n.s.pairFail); return@thread }
                val ctl = com.airsonic.sender.streaming.AirplayVideoController(device.host, hs)
                if (!ctl.connect()) { fail(L10n.s.setupFail); return@thread }
                videoCtl = ctl
                if (!ctl.play(url, 0.0)) { fail(L10n.s.setupFail); return@thread }
                onCastingStarted(device.name)
                while (casting) {
                    Thread.sleep(1000)
                    val info = ctl.playbackInfo() ?: continue
                    videoPos.value = info.first; videoDur.value = info.second
                }
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}")
            } finally {
                videoCleanup()
            }
        }
    }

    /** DLNA 投送：起本地 HTTP 服务 → SetAVTransportURI + Play → 轮询进度。视频/音频同路径。 */
    private fun startDlnaCast(context: Context, uri: Uri, device: AirDevice, isVideoFile: Boolean) {
        val app = context.applicationContext
        val controlUrl = device.controlUrl ?: run { fail(L10n.s.setupFail); return }
        phase.value = CastPhase.CONNECTING
        statusLine.value = "${L10n.s.connecting} ${device.name} …"
        casting = true; isVideo.value = isVideoFile
        worker = thread(name = "airsonic-dlna", isDaemon = true) {
            try {
                val src = ContentResolverRangeSource(app, uri)
                if (src.length <= 0) { fail(L10n.s.openFail); return@thread }
                val server = com.airsonic.sender.streaming.LocalMediaHttpServer(src)
                val port = server.start(); httpServer = server
                val localIp = localWifiIp() ?: run { fail("${L10n.s.castError}no ip"); return@thread }
                val url = "http://$localIp:$port${server.path}"
                val didl = buildDidl(device.name, url, src.mimeType, isVideoFile)
                val ctl = DlnaController(controlUrl); dlnaCtl = ctl
                if (!ctl.setUri(url, didl)) { fail("${L10n.s.castError}${ctl.lastError}"); return@thread }
                if (!ctl.play()) { fail("${L10n.s.castError}${ctl.lastError}"); return@thread }
                onCastingStarted(device.name)
                while (casting) {
                    Thread.sleep(1000)
                    val info = ctl.getPositionInfo() ?: continue
                    videoPos.value = info.first; videoDur.value = info.second
                }
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}")
            } finally {
                dlnaCleanup()
            }
        }
    }

    /** Sonos：捕获 PCM → AAC 实时流 → LiveAudioHttpServer → UPnP SetAVTransportURI+Play。 */
    private fun startSonosAudioStream(app: Context, cc: SystemAudioCapture, device: AirDevice) {
        val controlUrl = device.controlUrl ?: run { fail(L10n.s.setupFail); return }
        var live: com.airsonic.sender.streaming.LiveAudioHttpServer? = null
        var enc: com.airsonic.sender.streaming.AacStreamEncoder? = null
        try {
            val server = com.airsonic.sender.streaming.LiveAudioHttpServer()
            val port = server.start(); live = server
            val localIp = localIpForTarget(device.host) ?: run { fail("${L10n.s.castError}no ip"); return }
            val url = "http://$localIp:$port${server.path}"
            val encoder = com.airsonic.sender.streaming.AacStreamEncoder(
                sampleRate = 44100, channels = 2
            ) { frame -> server.push(frame) }
            encoder.start(); enc = encoder

            val didl = com.airsonic.sender.dlna.buildLiveAudioDidl(device.name, url)
            val ctl = DlnaController(controlUrl); dlnaCtl = ctl
            if (!ctl.setUri(url, didl)) { fail("${L10n.s.castError}${ctl.lastError}"); return }
            if (!ctl.play()) { fail("${L10n.s.castError}${ctl.lastError}"); return }
            // 把流地址记一处（诊断：确认下发给 Sonos 的拉流 URL 与本机网段）。
            statusLine.value = "流: http://$localIp:$port${server.path}"
            // 不静音手机：Sonos 路径下手机是「捕获源」而非竞争输出，
            // 把 STREAM_MUSIC 压到 0 会在 EMUI/华为上把被捕获的 App 一起静掉。
            onCastingStarted(device.name)
            // 捕获 → 编码 → 推流，直到停止（阻塞，让调用方 finally 统一 cleanup 捕获）
            var diagTick = 0          // 每 ~50 帧(约1s)刷新一次诊断；getTransportInfo 是网络调用，不能逐帧打
            while (casting) {
                val pcm = cc.readChunk(4096) ?: break
                if (pcm.isEmpty()) continue
                level.value = peakOf(pcm)
                encoder.encode(pcm)
                if (++diagTick >= 50) {
                    diagTick = 0
                    // getTransportInfo 失败返回 null（不阻断循环）；连接数=Sonos 是否真来取流。
                    activeCodec.value = "AAC｜S:${ctl.getTransportInfo() ?: "?"}｜流x${server.connections}｜$localIp:$port"
                }
            }
        } catch (t: Throwable) {
            fail("${L10n.s.castError}${t.message}")
        } finally {
            runCatching { dlnaCtl?.stop() }; dlnaCtl = null
            runCatching { enc?.stop() }
            runCatching { live?.stop() }
        }
    }

    private fun dlnaCleanup() {
        casting = false
        runCatching { dlnaCtl?.stop() }; dlnaCtl = null
        runCatching { httpServer?.stop() }; httpServer = null
        isVideo.value = false; videoPos.value = 0.0; videoDur.value = 0.0
        startedAt.value = 0L; level.value = 0f; worker = null
        if (phase.value != CastPhase.ERROR) { phase.value = CastPhase.IDLE; statusLine.value = L10n.s.stopped }
    }

    fun submitPin(pin: String) { pinQueue.offer(pin) }
    fun cancelPin() { pinQueue.offer(PIN_CANCEL) }

    // DLNA 控制是阻塞 SOAP（且 seek 后还要 sleep），调用方是 Compose 主线程 → 必须切后台线程避免 ANR。
    fun videoPause() { dlnaCtl?.let { c -> thread(isDaemon = true) { c.pause() }; return }; videoCtl?.rate(0) }
    fun videoResume() { dlnaCtl?.let { c -> thread(isDaemon = true) { c.play() }; return }; videoCtl?.rate(1) }
    fun videoSeek(sec: Double) {
        dlnaCtl?.let { c -> thread(isDaemon = true) { c.seek(sec); Thread.sleep(1000); c.play() }; return }
        videoCtl?.scrub(sec)
    }

    fun stop() {
        casting = false
        runCatching { capture?.stop() }
        runCatching { videoCtl?.stop() }
        runCatching { dlnaCtl?.stop() }
    }

    // ---- 内部 ----
    private fun onCastingStarted(name: String) {
        phase.value = CastPhase.CASTING
        statusLine.value = "${L10n.s.castingTo} $name"
        startedAt.value = android.os.SystemClock.elapsedRealtime()
    }

    private fun fail(msg: String) {
        if (!casting) return
        if (phase.value == CastPhase.ERROR) return   // 保留更靠内层、更具体的首个错误
        statusLine.value = msg
        phase.value = CastPhase.ERROR
    }

    private fun cleanup(app: Context, cap: SystemAudioCapture?) {
        casting = false
        runCatching { cap?.stop() }
        capture = null
        restorePhone(app)
        runCatching { CaptureProjectionService.stop(app) }
        startedAt.value = 0L
        level.value = 0f
        worker = null
        if (phase.value != CastPhase.ERROR) { phase.value = CastPhase.IDLE; statusLine.value = L10n.s.stopped }
    }

    private fun localWifiIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.hostAddress?.startsWith("169.254") == false }
            ?.hostAddress
    }.getOrNull()

    /** 优先返回与 target 同 /24 网段的本机 IPv4（避免华为多网卡选错）；否则回退 localWifiIp()。 */
    private fun localIpForTarget(target: String): String? {
        val prefix = target.substringBeforeLast('.', "")
        if (prefix.isNotEmpty()) {
            val match = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress &&
                        it.hostAddress?.startsWith("$prefix.") == true }
                    ?.hostAddress
            }.getOrNull()
            if (match != null) return match
        }
        return localWifiIp()
    }

    private fun videoCleanup() {
        casting = false
        runCatching { videoCtl?.stop() }; runCatching { videoCtl?.close() }; videoCtl = null
        runCatching { httpServer?.stop() }; httpServer = null
        isVideo.value = false; videoPos.value = 0.0; videoDur.value = 0.0
        startedAt.value = 0L; level.value = 0f; worker = null
        if (phase.value != CastPhase.ERROR) { phase.value = CastPhase.IDLE; statusLine.value = L10n.s.stopped }
    }

    /** 投送时压低手机媒体音量（仅 AirPlay 出声）；停止恢复。 */
    private fun mutePhone(app: Context) {
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
    }

    private fun restorePhone(app: Context) {
        if (savedVolume < 0) return
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
        }
        savedVolume = -1
    }

    data class DeviceProbe(val requiresAlac: Boolean, val requiresPin: Boolean)

    /** 一次 GET /info 同时判：是否只收 ALAC、是否需要密码/PIN 配对。 */
    private fun probeDevice(host: String): DeviceProbe = runCatching {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(host, 7000), 3000); sock.soTimeout = 3000
            sock.getOutputStream().apply {
                write("GET /info RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AirPlay/950.7.1\r\nX-Apple-HKP: 3\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
            }
            val ins = sock.getInputStream(); val buf = java.io.ByteArrayOutputStream(); val tmp = ByteArray(4096)
            var headerEnd = -1; var contentLen = -1
            while (true) {
                val n = ins.read(tmp); if (n < 0) break; buf.write(tmp, 0, n)
                val arr = buf.toByteArray()
                if (headerEnd < 0) {
                    var i = 0
                    while (i + 3 < arr.size) {
                        if (arr[i]==13.toByte()&&arr[i+1]==10.toByte()&&arr[i+2]==13.toByte()&&arr[i+3]==10.toByte()) {
                            headerEnd = i + 4
                            val header = String(arr, 0, i, Charsets.US_ASCII)
                            contentLen = Regex("(?i)Content-Length:\\s*(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                            break
                        }; i++
                    }
                }
                if (headerEnd >= 0 && contentLen >= 0 && arr.size >= headerEnd + contentLen) break
            }
            val all = buf.toByteArray()
            if (headerEnd < 0) return@runCatching DeviceProbe(false, false)
            val end = if (contentLen >= 0) minOf(all.size, headerEnd + contentLen) else all.size
            val pl = BPlist.decode(all.copyOfRange(headerEnd, end)) as? Map<*, *> ?: return@runCatching DeviceProbe(false, false)
            val saf = pl["supportedAudioFormatsExtended"] as? Map<*, *>
            val codes = (saf?.get("audioStream") as? List<*>)?.mapNotNull { (it as? Long)?.toInt() ?: (it as? Int) } ?: emptyList()
            val alac = codes.contains(18) && !codes.contains(11)
            val sf = ((pl["statusFlags"] as? Number)?.toLong()) ?: 0L
            val pin = (sf and 0x40L) != 0L || (sf and 0x8L) != 0L || (sf and 0x200L) != 0L
            DeviceProbe(alac, pin)
        }
    }.getOrDefault(DeviceProbe(false, false))

    private fun newHs(app: Context, device: AirDevice) =
        PairingHandshake(device.host, device.port, PairingStore.pairingId(app), PairingStore.ltSeed(app))

    /** 建立会话（对齐 pyatv）：会话通道**只在做过 pair-verify 的独立新连接**上。
     *  已配对/PIN配对完成 → 新连接 pair-verify(该连接即会话)；开放设备 → transient(同连接即会话)。 */
    private fun pairFor(app: Context, device: AirDevice): PairingHandshake? {
        if (pinAborted) return null
        // 1) 已配对 → 新连接 pair-verify（该连接即会话通道）
        if (PairingStore.isPaired(app, device.host)) {
            val hs = newHs(app, device)
            if (hs.pairVerify {}) return hs
            PairingStore.unpair(app, device.host)   // 对方已忘 → 清除重配
        }
        // 2) transient（开放设备：HomePod/Sonos/小米/无密码 Apple TV）→ 同连接即会话
        run {
            val hs = newHs(app, device)
            if (hs.pairSetup("3939", onStep = {}, transient = true)) return hs
        }
        // 3) 需要密码 → PIN pair-setup(M1-M6,持久化身份) → 然后**另开新连接** pair-verify 建会话
        if (!doPinSetup(app, device)) return null
        val hs = newHs(app, device)
        return if (hs.pairVerify {}) hs else { PairingStore.unpair(app, device.host); fail(L10n.s.pairFail); null }
    }

    /** PIN pair-setup（完整 M1-M6 交换 Ed25519 身份并持久化），不在此连接建会话。 */
    private fun doPinSetup(app: Context, device: AirDevice): Boolean {
        val hs = newHs(app, device)
        if (!hs.pairPinStart()) { fail(L10n.s.pairFail); pinAborted = true; return false }
        pinQueue.clear()
        pinNonce.value++
        pinRequest.value = device.name
        val pin = try { pinQueue.poll(120, java.util.concurrent.TimeUnit.SECONDS) } finally { pinRequest.value = null }
        if (pin == null || pin == PIN_CANCEL) { pinAborted = true; return false }
        if (!hs.pairSetup(pin, onStep = {}, transient = false)) { fail(L10n.s.pairFail); pinAborted = true; return false }
        PairingStore.markPaired(app, device.host)
        return true
    }

    /** 配对(按需PIN) + SETUP(按/info选编码，失败换编码再试；二次走verify不再弹PIN)。 */
    private fun connect(app: Context, device: AirDevice): Pair<AirplayStreamSession, AirplayStreamSession.StreamResult>? {
        pinAborted = false
        val probe = probeDevice(device.host)
        fun attempt(useAlac: Boolean): Pair<AirplayStreamSession, AirplayStreamSession.StreamResult>? {
            val hs = pairFor(app, device) ?: return null
            val session = AirplayStreamSession(device.host, hs)
            val result = session.setup(useAlac = useAlac) {} ?: return null
            activeCodec.value = if (useAlac) "ALAC" else "PCM"
            return session to result
        }
        // 「强制 ALAC」开关优先；否则用 GET /info 自动探测结果。SETUP 对 PCM/ALAC 都会成功，
        // 故回退仅在 SETUP 真失败时触发。Sonos 等只收 ALAC 的设备需开开关或探测命中。
        val preferAlac = forceAlac.value || probe.requiresAlac
        return attempt(preferAlac) ?: attempt(!preferAlac)
    }

    private fun peakOf(pcm: ByteArray): Float {
        var peak = 0
        var i = 0
        val n = minOf(pcm.size - 1, 2048)
        while (i < n) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }
}