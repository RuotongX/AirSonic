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
import com.airsonic.sender.discovery.pickLanIpForTarget
import com.airsonic.sender.dlna.DlnaController
import com.airsonic.sender.pairing.DeviceProbe
import com.airsonic.sender.pairing.parseDeviceProbe
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
    /** 本次会话实际投送的设备名（会话期间锁定）。UI 必须显示它而非 selected——
     *  投送中 mDNS 抖动会让 selected 漂到别的设备，造成「自动投到小米」的假象。 */
    val castingDeviceName = mutableStateOf("")

    /** 强制使用 ALAC 编码（Sonos 等只收 ALAC 的设备调试用；持久化）。HomePod 走自动探测不受影响。 */
    val forceAlac = mutableStateOf(false)
    /** Sonos 直播改投无限长 WAV（AAC 电台管线不出声时的兼容兜底；持久化）。 */
    val sonosWav = mutableStateOf(true)
    /** 当前会话实际使用的音频编码标签（"ALAC"/"PCM"），供 UI 调试显示。 */
    val activeCodec = mutableStateOf("")

    private const val PREFS = "airsonic_prefs"

    fun loadPrefs(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        forceAlac.value = p.getBoolean("force_alac", false)
        sonosWav.value = p.getBoolean("sonos_wav", true)
    }

    fun setForceAlac(context: Context, v: Boolean) {
        forceAlac.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("force_alac", v).apply()
    }

    fun setSonosWav(context: Context, v: Boolean) {
        sonosWav.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("sonos_wav", v).apply()
    }

    private var discovery: AirplayDiscovery? = null
    @Volatile private var casting = false
    /** 供前台服务判断会话是否仍在跑（进程被杀重启时决定 stopSelf）。 */
    val isActive: Boolean get() = casting
    /** 会话代号：每次开始投送 +1。旧会话的 worker/finally 凭代号判断自己是否已被新会话接管，
     *  避免「停止→立即重投」时旧会话的 cleanup 把新会话的前台服务/状态掐死。 */
    @Volatile private var sessionGen = 0
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
                // 自动选中第一台可投设备（若尚未选）。投送中禁止漂移：mDNS 抖动会把 selected
                // 清空再选到别的设备，UI 看起来像「自动投到小米」。
                if (selected.value == null && !casting && isCastable(device)) selected.value = device
                // 注：Sonos 识别/路由改为「投送时即时探测 :1400」（见 startSystemAudioCast），
                // 发现期不再起探测线程，避免给本就脆弱的 NsdManager 发现添乱。
            }
            override fun onDeviceLost(device: AirDevice) {
                // NsdManager 的 onServiceLost 是未解析回调（host=null/port=0 → id=":0"），
                // 按 id 永远匹配不到 → 幽灵设备。mDNS 里服务名才是身份，按 name 删。
                devices.removeAll { it.name == device.name }
                if (selected.value?.name == device.name) selected.value = null
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
                    if (selected.value == null && !casting && isCastable(device)) selected.value = device
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
        val gen = ++sessionGen
        CaptureProjectionService.start(app)
        worker = thread(name = "airsonic-cast", isDaemon = true) {
            var cap: SystemAudioCapture? = null
            var projection: android.media.projection.MediaProjection? = null
            try {
                var waited = 0
                while (!CaptureProjectionService.isForeground && waited < 2000) { Thread.sleep(50); waited += 50 }
                val pm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = pm.getMediaProjection(resultCode, data)
                val cc = SystemAudioCapture(); cap = cc
                if (!cc.start(projection)) { fail(L10n.s.captureFail, gen); return@thread }
                capture = cc
                // Sonos：走 UPnP 实时流（不进 AirPlay）。
                // 发现阶段的 :1400 探测时好时坏（浏览器证 :1400 可达且快），故在投送时对「未知类型」
                // 设备多探几次把它探可靠；已识别的 AirPlay 设备（HomePod/AppleTV/Mac/小米）跳过，不增延迟。
                // SSDP 发现的 DLNA 条目（Sonos 会同时以两种形态出现）同样探 :1400 归位。
                var probeReason = "skip"
                val sonosCtl: String? = when {
                    device.type == DeviceType.SONOS && device.controlUrl != null -> device.controlUrl.also { probeReason = "preset" }
                    device.type == DeviceType.SONOS || device.type == DeviceType.UNKNOWN
                        || device.type == DeviceType.DLNA -> {
                        var c: String? = null
                        repeat(4) {
                            if (c == null && device.host.isNotEmpty() && casting && gen == sessionGen) {
                                val (ctl, reason) = com.airsonic.sender.dlna.probeSonosWithReason(device.host, 3000, 3000)
                                c = ctl; probeReason = reason
                                if (c == null) Thread.sleep(500)
                            }
                        }
                        c
                    }
                    else -> null
                }
                if (!casting || gen != sessionGen) return@thread
                if (sonosCtl != null) {
                    startSonosAudioStream(app, cc, device.copy(type = DeviceType.SONOS, controlUrl = sonosCtl), gen)
                    return@thread
                }
                if (device.type == DeviceType.SONOS || device.type == DeviceType.UNKNOWN || device.type == DeviceType.DLNA) {
                    android.util.Log.w("CastEngine", ":1400 probe failed for ${device.host} ($probeReason) → falling back to AirPlay")
                }
                val (session, result) = connect(app, device)
                    ?: run { fail(L10n.s.setupFail, gen); return@thread }
                // connect 可能阻塞很久（PIN 配对最长 120s）：返回后先确认会话没被接管再动全局状态
                if (!casting || gen != sessionGen) return@thread
                mutePhone(app)
                onCastingStarted(device.name)
                // 路由诊断：走到这里＝没进 Sonos UPnP、回退了 AirPlay。把类型/host/探测原因打到投送页那行，便于定位。
                activeCodec.value = "${activeCodec.value}｜t=${device.type}｜h=${device.host}｜1400=$probeReason"
                session.streamCapturedPcm(
                    result = result, channels = 2,
                    isCancelled = { !casting || gen != sessionGen },
                    nextChunk = {
                        val c = cc.readChunk(4096)
                        if (c != null && c.size >= 64) level.value = peakOf(c)
                        c
                    }
                ) {}
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                cleanup(app, cap, projection, gen)
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
        val gen = ++sessionGen
        worker = thread(name = "airsonic-filecast", isDaemon = true) {
            var pfd: ParcelFileDescriptor? = null
            try {
                val (session, result) = connect(app, device)
                    ?: run { fail(L10n.s.setupFail, gen); return@thread }
                pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: run { fail(L10n.s.openFail, gen); return@thread }
                if (!casting || gen != sessionGen) return@thread   // connect(PIN 配对)期间可能已被新会话接管
                mutePhone(app)
                onCastingStarted(device.name)
                session.streamAudio(result, pfd.fileDescriptor, realtimePacing = true, isCancelled = { !casting || gen != sessionGen }) {}
                if (casting && gen == sessionGen) statusLine.value = L10n.s.playFinished
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                runCatching { pfd?.close() }
                cleanup(app, null, null, gen)
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
        val gen = ++sessionGen
        worker = thread(name = "airsonic-video", isDaemon = true) {
            var server: com.airsonic.sender.streaming.LocalMediaHttpServer? = null
            var ctl: com.airsonic.sender.streaming.AirplayVideoController? = null
            try {
                val src = ContentResolverRangeSource(app, uri, isVideo = true)
                if (src.length <= 0) { fail(L10n.s.openFail, gen); return@thread }
                server = com.airsonic.sender.streaming.LocalMediaHttpServer(src)
                val port = server.start(); httpServer = server
                val localIp = localWifiIp() ?: run { fail("${L10n.s.castError}no ip", gen); return@thread }
                val url = "http://$localIp:$port${server.path}"
                val hs = pairFor(app, device) ?: run { if (phase.value != CastPhase.ERROR) fail(L10n.s.pairFail, gen); return@thread }
                val c = com.airsonic.sender.streaming.AirplayVideoController(device.host, hs)
                ctl = c
                if (!c.connect()) { fail(L10n.s.setupFail, gen); return@thread }
                videoCtl = c
                if (!c.play(url, 0.0)) { fail(L10n.s.setupFail, gen); return@thread }
                onCastingStarted(device.name)
                while (casting && gen == sessionGen) {
                    Thread.sleep(1000)
                    val info = c.playbackInfo() ?: continue
                    videoPos.value = info.first; videoDur.value = info.second
                }
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                videoCleanup(gen, server, ctl)
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
        val gen = ++sessionGen
        worker = thread(name = "airsonic-dlna", isDaemon = true) {
            var server: com.airsonic.sender.streaming.LocalMediaHttpServer? = null
            var ctl: DlnaController? = null
            try {
                val src = ContentResolverRangeSource(app, uri, isVideo = isVideoFile)
                if (src.length <= 0) { fail(L10n.s.openFail, gen); return@thread }
                server = com.airsonic.sender.streaming.LocalMediaHttpServer(src)
                val port = server.start(); httpServer = server
                val localIp = localWifiIp() ?: run { fail("${L10n.s.castError}no ip", gen); return@thread }
                val url = "http://$localIp:$port${server.path}"
                val didl = buildDidl(device.name, url, src.mimeType, isVideoFile, sizeBytes = src.length)
                val c = DlnaController(controlUrl); ctl = c; dlnaCtl = c
                if (!c.setUri(url, didl)) { fail("${L10n.s.castError}${c.lastError}", gen); return@thread }
                if (!c.play()) { fail("${L10n.s.castError}${c.lastError}", gen); return@thread }
                onCastingStarted(device.name)
                while (casting && gen == sessionGen) {
                    Thread.sleep(1000)
                    val info = c.getPositionInfo() ?: continue
                    videoPos.value = info.first; videoDur.value = info.second
                }
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                dlnaCleanup(gen, server, ctl)
            }
        }
    }

    /**
     * Sonos：捕获 PCM → 实时流 → LiveAudioHttpServer → UPnP SetAVTransportURI+Play。
     * 默认 AAC 经「电台管线」（x-rincon-mp3radio:// + SoCo 同款电台 DIDL——新固件拒裸 http 电台 URI）；
     * 开了 [sonosWav] 则改投无限长 WAV「超长曲目」（假大 Content-Length，swyh-rs 同款兜底）。
     */
    private fun startSonosAudioStream(app: Context, cc: SystemAudioCapture, device: AirDevice, gen: Int) {
        val controlUrl = device.controlUrl ?: run { fail(L10n.s.setupFail); return }
        val wav = sonosWav.value
        var live: com.airsonic.sender.streaming.LiveAudioHttpServer? = null
        var enc: com.airsonic.sender.streaming.AacStreamEncoder? = null
        var ctl: DlnaController? = null
        val pumpStop = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val server = if (wav)
                com.airsonic.sender.streaming.LiveAudioHttpServer(
                    contentType = "audio/wav", pathExt = "wav",
                    fakeContentLength = 0xFFFFFFFFL,
                    streamHeader = com.airsonic.sender.streaming.wavStreamHeader(44100, 2, 16),
                )
            else com.airsonic.sender.streaming.LiveAudioHttpServer()
            val port = server.start(); live = server
            val localIp = localIpForTarget(device.host) ?: run { fail("${L10n.s.castError}no ip", gen); return }
            val httpUrl = "http://$localIp:$port${server.path}"
            if (!wav) {
                val encoder = com.airsonic.sender.streaming.AacStreamEncoder(
                    sampleRate = 44100, channels = 2
                ) { frame -> server.push(frame) }
                encoder.start(); enc = encoder
            }

            val castUri = if (wav) httpUrl else com.airsonic.sender.dlna.sonosRadioUri(httpUrl)
            val didl = if (wav) com.airsonic.sender.dlna.buildLiveWavDidl(device.name, httpUrl)
                       else com.airsonic.sender.dlna.buildSonosRadioDidl(device.name)
            val c = DlnaController(controlUrl); ctl = c; dlnaCtl = c
            if (!casting || gen != sessionGen) return
            // 提前暴露路由诊断：setUri 若超时也能看出控制端点/本机流地址是否合理（多网卡选错等）
            val ctlHost = controlUrl.removePrefix("http://").substringBefore("/")
            activeCodec.value = "${if (wav) "WAV" else "AAC"}｜投…｜ctl=$ctlHost｜流=$localIp:$port"
            if (!c.setUri(castUri, didl)) { fail("${L10n.s.castError}${c.lastError}", gen); return }
            // 关键：Sonos 对电台流 Play 会先连流、等首个音频帧确认格式才返回。
            // 若 play 在推流之前发、流是空的，Play 必然等到 SOAP 超时。
            // 故先起捕获→(编码)→推流线程，让流产出数据，再 play()。
            val fmtLabel = if (wav) "WAV" else "AAC"
            val pump = thread(isDaemon = true, name = "airsonic-sonos-pump") {
                // 整体 try/catch：play 失败路径 finally 会先停编码器，pump 撞上已释放的
                // MediaCodec 会抛 IllegalStateException——线程级未捕获异常会崩整个 app。
                try {
                    while (!pumpStop.get() && casting && gen == sessionGen) {
                        val pcm = cc.readChunk(4096) ?: break
                        if (pcm.isEmpty()) continue
                        level.value = peakOf(pcm)
                        if (wav) server.push(pcm) else enc?.encode(pcm)
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("CastEngine", "sonos pump exit: ${t.javaClass.simpleName}:${t.message}")
                }
            }
            Thread.sleep(500)   // 让编码器先产几帧，Sonos 一连流即有数据可拉
            if (!c.play()) { fail("${L10n.s.castError}${c.lastError}", gen); return }
            // 不静音手机：Sonos 路径下手机是「捕获源」而非竞争输出，
            // 把 STREAM_MUSIC 压到 0 会在 EMUI/华为上把被捕获的 App 一起静掉。
            onCastingStarted(device.name)
            // 诊断走独立低频线程：getTransportInfo 是阻塞 SOAP（最坏 8s），
            // 绝不能插在捕获热循环里（AudioRecord 缓冲仅几百毫秒，卡一次就 overrun 爆音/断流）。
            thread(isDaemon = true, name = "airsonic-sonos-diag") {
                while (casting && gen == sessionGen && dlnaCtl === c) {
                    activeCodec.value = "$fmtLabel｜S:${c.getTransportInfo() ?: "?"}｜流x${server.connections}｜$localIp:$port"
                    runCatching { Thread.sleep(3000) }
                }
            }
            pump.join()   // 阻塞到停止（capture 被外层 cleanup 关 → readChunk null → pump 退出）
        } catch (t: Throwable) {
            fail("${L10n.s.castError}${t.message}", gen)
        } finally {
            pumpStop.set(true)   // 先叫停 pump，再停编码器/服务，缩小 encode-after-release 竞窗
            // SOAP Stop 是阻塞网络请求(最长 connect3s+read10s)，切后台——否则挡住外层 cleanup，
            // 用户点「停止」后 UI 要卡好几秒才变「已停止」。
            ctl?.let { c2 -> thread(isDaemon = true, name = "airsonic-sonos-stop") { runCatching { c2.stop() } } }
            if (dlnaCtl === ctl) dlnaCtl = null   // 只清自己这代的控制器，别动新会话的
            runCatching { enc?.stop() }
            runCatching { live?.stop() }
        }
    }

    private fun dlnaCleanup(
        gen: Int,
        ownServer: com.airsonic.sender.streaming.LocalMediaHttpServer? = null,
        ownCtl: DlnaController? = null,
    ) {
        // 自己的资源无条件关；网络 Stop 切后台(阻塞 SOAP 会拖慢 UI 复位好几秒)
        ownCtl?.let { c -> thread(isDaemon = true, name = "airsonic-dlna-stop") { runCatching { c.stop() } } }
        runCatching { ownServer?.stop() }
        if (gen != sessionGen) return        // 已被新会话接管：别动全局引用/UI 状态
        casting = false
        if (dlnaCtl === ownCtl) dlnaCtl = null
        if (httpServer === ownServer) httpServer = null
        isVideo.value = false; videoPos.value = 0.0; videoDur.value = 0.0
        startedAt.value = 0L; level.value = 0f; worker = null; castingDeviceName.value = ""
        if (phase.value != CastPhase.ERROR) { phase.value = CastPhase.IDLE; statusLine.value = L10n.s.stopped }
    }

    fun submitPin(pin: String) { pinQueue.offer(pin) }
    fun cancelPin() { pinQueue.offer(PIN_CANCEL) }

    // DLNA 控制是阻塞 SOAP（且 seek 后还要 sleep），调用方是 Compose 主线程 → 必须切后台线程避免 ANR。
    // 注意：AirPlay 的 videoCtl 走加密 socket，主线程直调会 NetworkOnMainThreadException 被吞掉→按钮静默失灵，必须切后台。
    fun videoPause() { dlnaCtl?.let { c -> thread(isDaemon = true) { c.pause() }; return }; videoCtl?.let { c -> thread(isDaemon = true) { c.rate(0) } } }
    fun videoResume() { dlnaCtl?.let { c -> thread(isDaemon = true) { c.play() }; return }; videoCtl?.let { c -> thread(isDaemon = true) { c.rate(1) } } }
    fun videoSeek(sec: Double) {
        dlnaCtl?.let { c -> thread(isDaemon = true) { c.seek(sec); Thread.sleep(1000); c.play() }; return }
        videoCtl?.let { c -> thread(isDaemon = true) { c.scrub(sec) } }
    }

    fun stop() {
        casting = false
        runCatching { capture?.stop() }
        // SOAP/RTSP 停止是阻塞网络调用，调用方是 Compose 主线程 → 必须切后台
        // （直接调会 NetworkOnMainThreadException 被吞掉，等于没发）。
        val v = videoCtl; val d = dlnaCtl
        if (v != null || d != null) thread(isDaemon = true, name = "airsonic-stop") {
            runCatching { v?.stop() }
            runCatching { d?.stop() }
        }
    }

    // ---- 内部 ----
    private fun onCastingStarted(name: String) {
        phase.value = CastPhase.CASTING
        statusLine.value = "${L10n.s.castingTo} $name"
        castingDeviceName.value = name
        startedAt.value = android.os.SystemClock.elapsedRealtime()
    }

    private fun fail(msg: String, gen: Int = -1) {
        if (gen >= 0 && gen != sessionGen) return    // 旧会话 worker 迟到的失败：别污染新会话状态
        if (!casting) return
        if (phase.value == CastPhase.ERROR) return   // 保留更靠内层、更具体的首个错误
        statusLine.value = msg
        phase.value = CastPhase.ERROR
    }

    private fun cleanup(
        app: Context,
        cap: SystemAudioCapture?,
        projection: android.media.projection.MediaProjection?,
        gen: Int,
    ) {
        runCatching { cap?.stop() }                  // 自己的捕获器总要关
        runCatching { projection?.stop() }           // MediaProjection 也要停，否则系统投屏指示常驻、干扰下次授权
        if (gen != sessionGen) return                // 已被新会话接管：别动全局状态/前台服务
        casting = false
        capture = null
        restorePhone(app)
        runCatching { CaptureProjectionService.stop(app) }
        startedAt.value = 0L
        level.value = 0f
        worker = null
        castingDeviceName.value = ""
        if (phase.value != CastPhase.ERROR) { phase.value = CastPhase.IDLE; statusLine.value = L10n.s.stopped }
    }

    /** 枚举本机 IPv4（非回环）候选。选哪个交给纯函数 [pickLanIpForTarget]。 */
    private fun localIpv4Candidates(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun localWifiIp(): String? = pickLanIpForTarget(localIpv4Candidates(), "")

    /** 优先返回与 target 同 /24 网段的本机 IPv4（避免华为多网卡选错）；否则回退第一个可用。 */
    private fun localIpForTarget(target: String): String? = pickLanIpForTarget(localIpv4Candidates(), target)

    private fun videoCleanup(
        gen: Int,
        ownServer: com.airsonic.sender.streaming.LocalMediaHttpServer? = null,
        ownCtl: com.airsonic.sender.streaming.AirplayVideoController? = null,
    ) {
        // 先无条件关掉本 worker 自己建的 server/ctl（即使已被新会话接管，也要关自己的，否则 socket/连接泄漏）
        // RTSP stop 是阻塞网络请求 → 切后台，别挡 UI 复位；close 跟在 stop 后同线程做
        ownCtl?.let { c -> thread(isDaemon = true, name = "airsonic-video-stop") {
            runCatching { c.stop() }; runCatching { c.close() }
        } }
        runCatching { ownServer?.stop() }
        if (gen != sessionGen) return     // 已被新会话接管：别动全局引用/UI 状态
        casting = false
        if (videoCtl === ownCtl) videoCtl = null
        if (httpServer === ownServer) httpServer = null
        isVideo.value = false; videoPos.value = 0.0; videoDur.value = 0.0
        startedAt.value = 0L; level.value = 0f; worker = null; castingDeviceName.value = ""
        if (phase.value != CastPhase.ERROR) { phase.value = CastPhase.IDLE; statusLine.value = L10n.s.stopped }
    }

    /** 投送时压低手机媒体音量（仅 AirPlay 出声）；停止恢复。 */
    private fun mutePhone(app: Context) {
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // 只在尚未保存时记录原音量：防快速重投时旧会话已压到 0、新会话把 0 当原值存下，导致音量被永久吃掉
            if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
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

    /** 一次 GET /info 同时判：是否只收 ALAC、是否需要密码/PIN 配对。判定逻辑见 [parseDeviceProbe]。 */
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
            parseDeviceProbe(pl)
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
            hs.knownAccessoryLtpk = PairingStore.accessoryLtpk(app, device.host)   // 有存即强制验签
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
        hs.knownAccessoryLtpk = PairingStore.accessoryLtpk(app, device.host)
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
        hs.lastAccessoryLtpk?.let { PairingStore.saveAccessoryLtpk(app, device.host, it) }   // M6 已验签的 LTPK 落库
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

    private fun peakOf(pcm: ByteArray): Float = com.airsonic.sender.streaming.pcmPeak(pcm)
}