// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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
import com.airsonic.sender.api.VolumeController
import com.airsonic.sender.api.AirplayVolumeController
import com.airsonic.sender.api.UpnpVolumeController
import com.airsonic.sender.api.GainVolumeController
import com.airsonic.sender.dlna.RenderingControlController
import com.airsonic.sender.streaming.scalePcm16
import com.airsonic.sender.streaming.AirplayStreamSession
import com.airsonic.sender.streaming.BPlist
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    const val SPECTRUM_BANDS = 24
    /** 实时音频幅度 0..1，驱动律动动图。 */
    val level = mutableStateOf(0f)
    /** 实时频谱：[SPECTRUM_BANDS] 个对数频段幅度 0..1，驱动频谱可视化。 */
    val spectrum = mutableStateOf(FloatArray(SPECTRUM_BANDS))
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

    @Volatile private var meterTick = 0
    /** 从捕获 PCM 更新律动+频谱；FFT 降频到每 2 块(~20fps)，不进推流高频热点。 */
    private fun updateMeters(pcm: ByteArray) {
        if (pcm.size < 64) return
        level.value = peakOf(pcm)
        if (meterTick++ % 2 == 0) spectrum.value = com.airsonic.sender.streaming.spectrumBands(pcm, SPECTRUM_BANDS)
    }

    private const val PREFS = "airsonic_prefs"

    fun loadPrefs(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        forceAlac.value = p.getBoolean("force_alac", false)
        sonosWav.value = p.getBoolean("sonos_wav", true)
        // 强杀/崩溃后冷启动：上次 DLNA 会话的 Stop 没发出去（电视可能还在播）→ 补发 Stop 清场
        val stale = p.getString("last_dlna_ctl", null)
        if (stale != null) {
            p.edit().remove("last_dlna_ctl").apply()
            thread(isDaemon = true, name = "airsonic-stale-stop") { runCatching { DlnaController(stale).stop() } }
            statusLine.value = L10n.s.cleanedLastSession
        }
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
    /** 会话代号：每次开始投送 +1。保留的捕获/诊断线程(Sonos pump 等)凭它判断是否已被新会话接管而退出，
     *  与协程的结构化取消并存(协程靠 sessionJob.cancel())。 */
    @Volatile private var sessionGen = 0
    private var capture: SystemAudioCapture? = null
    private var savedVolume: Int = -1
    private var httpServer: com.airsonic.sender.streaming.LocalMediaHttpServer? = null
    private var videoCtl: com.airsonic.sender.streaming.AirplayVideoController? = null
    private var dlnaDiscovery: DlnaDiscovery? = null
    @Volatile private var dlnaCtl: DlnaController? = null
    @Volatile private var volumeController: VolumeController? = null
    /** 投送中音量百分比 0..100，UI 滑块绑定。 */
    val volumePct = mutableStateOf(50)
    /** 音量下发被设备拒绝/无响应（海信 VIDAA 等）→ UI 在滑块下提示用遥控器。 */
    val volumeWarn = mutableStateOf(false)
    /** 是否静音。 */
    val muted = mutableStateOf(false)
    /** 是否已绑定音量后端（驱动 UI 控件显隐）。 */
    val volumeActive = mutableStateOf(false)
    /** 节流：拖动时最后值胜出，避免 SOAP/RTSP 往返被冲爆。 */
    @Volatile private var pendingVolumePct: Int = -1
    @Volatile private var volumeThrottleJob: Job? = null
    // ---- T2 协程化（四条投送路径全部迁 engineScope；仅 Sonos pump/诊断为保留线程，靠 casting/gen 退出）----
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var sessionJob: Job? = null

    // ---- 后台保活 ----
    // 投送期间持 Wi-Fi 高吞吐锁 + 部分唤醒锁。否则 app 一切后台，Wi-Fi 进省电(DTIM 间隙)、
    // CPU 降频，实时流断供——电视端播放器缓冲耗尽就报「服务断开」(坚果/当贝实测)。
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var castLockApp: Context? = null   // 释锁时清「DLNA 会话落盘」要用

    @Synchronized
    private fun castLocksAcquire(app: Context) {
        castLockApp = app
        if (wifiLock == null) {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airsonic:cast")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airsonic:cast")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    @Synchronized
    private fun castLocksRelease() {
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null; wakeLock = null
        clearDlnaSession()   // 每条干净收尾都过这里 → 会话落盘随正常结束清除
    }

    /** DLNA 会话控制端点落盘：进程被强杀时 Stop 发不出去，电视可能还在播；下次冷启动补发（见 loadPrefs）。 */
    private fun persistDlnaSession(app: Context, controlUrl: String) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_dlna_ctl", controlUrl).apply()
    }

    private fun clearDlnaSession() {
        castLockApp?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("last_dlna_ctl")?.apply()
    }
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
        castLocksAcquire(app)
        val gen = ++sessionGen
        sessionJob?.cancel()   // 停掉可能在跑的协程会话(DLNA)，与线程路径互斥
        CaptureProjectionService.start(app)
        // 【T2 阶段3 已迁协程】MediaProjection 等待用 delay；探测/connect/streamCapturedPcm 全 withContext(IO)；
        // Sonos 子流程 startSonosAudioStream 仍是阻塞 fun(pump 捕获热循环保留线程)，从 IO 调用。
        sessionJob = engineScope.launch {
            var cap: SystemAudioCapture? = null
            var projection: android.media.projection.MediaProjection? = null
            try {
                var waited = 0
                while (!CaptureProjectionService.isForeground && waited < 2000) { delay(50); waited += 50 }
                val cc = withContext(Dispatchers.IO) {
                    val pm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val proj = pm.getMediaProjection(resultCode, data)
                    projection = proj
                    SystemAudioCapture().takeIf { it.start(proj) }
                } ?: run { fail(L10n.s.captureFail, gen); return@launch }
                cap = cc; capture = cc
                // Sonos：走 UPnP 实时流（不进 AirPlay）。投送时对「未知/Sonos/DLNA」型设备探 :1400 归位，
                // 已识别的 AirPlay 设备（HomePod/AppleTV/Mac/小米）跳过，不增延迟。
                var probeReason = "skip"
                val sonosCtl: String? = when {
                    device.type == DeviceType.SONOS && device.controlUrl != null -> device.controlUrl.also { probeReason = "preset" }
                    device.type == DeviceType.SONOS || device.type == DeviceType.UNKNOWN
                        || device.type == DeviceType.DLNA -> {
                        var c: String? = null
                        repeat(4) {
                            if (c == null && device.host.isNotEmpty() && casting && gen == sessionGen) {
                                val (ctl, reason) = withContext(Dispatchers.IO) { com.airsonic.sender.dlna.probeSonosWithReason(device.host, 3000, 3000) }
                                c = ctl; probeReason = reason
                                if (c == null) delay(500)
                            }
                        }
                        c
                    }
                    else -> null
                }
                if (!isActive || !casting || gen != sessionGen) return@launch
                if (sonosCtl != null) {
                    withContext(Dispatchers.IO) { startUpnpLiveAudioStream(app, cc, device.copy(type = DeviceType.SONOS, controlUrl = sonosCtl), gen, sonos = true) }
                    return@launch
                }
                // 通用 DLNA 渲染器（坚果 N1S 等）：非 Sonos、非 AirPlay，但有 AVTransport 控制端点
                // （「投本地」走的同一个）。系统音频走通用 UPnP 实时流，绝不能退回 AirPlay 配对（必失败）。
                if (device.type == DeviceType.DLNA && device.controlUrl != null) {
                    withContext(Dispatchers.IO) { startUpnpLiveAudioStream(app, cc, device, gen, sonos = false) }
                    return@launch
                }
                if (device.type == DeviceType.SONOS || device.type == DeviceType.UNKNOWN) {
                    android.util.Log.w("CastEngine", ":1400 probe failed for ${device.host} ($probeReason) → falling back to AirPlay")
                }
                val pair = withContext(Dispatchers.IO) { connect(app, device) }
                    ?: run { fail(L10n.s.setupFail, gen); return@launch }
                val (session, result) = pair
                if (!isActive || !casting || gen != sessionGen) return@launch   // connect(PIN)期间可能已被接管
                bindVolume(AirplayVolumeController(session), defaultPct = 50)
                mutePhone(app)
                onCastingStarted(device.name)
                activeCodec.value = "${activeCodec.value}｜t=${device.type}｜h=${device.host}｜1400=$probeReason"
                withContext(Dispatchers.IO) {
                    session.streamCapturedPcm(
                        result = result, channels = 2,
                        isCancelled = { !isActive || !casting || gen != sessionGen },
                        nextChunk = {
                            val c = cc.readChunk(4096)
                            if (c != null) updateMeters(c)
                            c
                        }
                    ) {}
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                withContext(NonCancellable) { cleanup(app, cap, projection, gen) }
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
        castLocksAcquire(app)
        val gen = ++sessionGen
        sessionJob?.cancel()
        // 【T2 阶段2 已迁协程】connect(含 PIN 配对,可阻塞 120s)与 streamAudio 全 withContext(IO)；
        // 停止=cancel(且 stop() 里 cancelPin() 解开 PIN 等待)。
        sessionJob = engineScope.launch {
            var pfd: ParcelFileDescriptor? = null
            try {
                val pair = withContext(Dispatchers.IO) { connect(app, device) }
                    ?: run { fail(L10n.s.setupFail, gen); return@launch }
                val (session, result) = pair
                pfd = withContext(Dispatchers.IO) { app.contentResolver.openFileDescriptor(uri, "r") }
                    ?: run { fail(L10n.s.openFail, gen); return@launch }
                if (!isActive || !casting || gen != sessionGen) return@launch   // connect 期间可能已被接管
                mutePhone(app)
                onCastingStarted(device.name)
                val fd = pfd.fileDescriptor
                withContext(Dispatchers.IO) {
                    session.streamAudio(result, fd, realtimePacing = true,
                        isCancelled = { !isActive || !casting || gen != sessionGen }) {}
                }
                if (isActive && casting && gen == sessionGen) statusLine.value = L10n.s.playFinished
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                withContext(NonCancellable) {
                    runCatching { pfd?.close() }
                    cleanup(app, null, null, gen)
                }
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
        castLocksAcquire(app)
        val gen = ++sessionGen
        sessionJob?.cancel()
        // 【T2 阶段4 已迁协程】connect/play/进度轮询全 withContext(IO)/delay；停止=cancel。
        sessionJob = engineScope.launch {
            var server: com.airsonic.sender.streaming.LocalMediaHttpServer? = null
            var ctl: com.airsonic.sender.streaming.AirplayVideoController? = null
            try {
                val src = ContentResolverRangeSource(app, uri, isVideo = true)
                if (src.length <= 0) { fail(L10n.s.openFail, gen); return@launch }
                server = com.airsonic.sender.streaming.LocalMediaHttpServer(src)
                val port = withContext(Dispatchers.IO) { server.start() }; httpServer = server
                val localIp = localWifiIp() ?: run { fail("${L10n.s.castError}no ip", gen); return@launch }
                val url = "http://$localIp:$port${server.path}"
                val hs = withContext(Dispatchers.IO) { pairFor(app, device) }
                    ?: run { if (phase.value != CastPhase.ERROR) fail(L10n.s.pairFail, gen); return@launch }
                val c = com.airsonic.sender.streaming.AirplayVideoController(device.host, hs)
                ctl = c
                if (!withContext(Dispatchers.IO) { c.connect() }) { fail(L10n.s.setupFail, gen); return@launch }
                videoCtl = c
                if (!withContext(Dispatchers.IO) { c.play(url, 0.0) }) { fail(L10n.s.setupFail, gen); return@launch }
                onCastingStarted(device.name)
                while (isActive && casting && gen == sessionGen) {
                    delay(1000)
                    val info = withContext(Dispatchers.IO) { c.playbackInfo() } ?: continue
                    videoPos.value = info.first; videoDur.value = info.second
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                withContext(NonCancellable) { videoCleanup(gen, server, ctl) }
            }
        }
    }

    /**
     * DLNA 投送：起本地 HTTP 服务 → SetAVTransportURI + Play → 轮询进度。视频/音频同路径。
     * 【T2 阶段1 已迁协程】会话跑在 [engineScope] 上，停止 = sessionJob.cancel()（结构化取消），
     * 阻塞 SOAP 全部 withContext(IO)；finally 在 NonCancellable 里清理。casting/gen 检查暂留，
     * 与尚未迁移的线程路径互斥（迁完可删）。
     */
    private fun startDlnaCast(context: Context, uri: Uri, device: AirDevice, isVideoFile: Boolean) {
        val app = context.applicationContext
        val controlUrl = device.controlUrl ?: run { fail(L10n.s.setupFail); return }
        phase.value = CastPhase.CONNECTING
        statusLine.value = "${L10n.s.connecting} ${device.name} …"
        casting = true; isVideo.value = isVideoFile
        castLocksAcquire(app)
        val gen = ++sessionGen
        sessionJob?.cancel()
        sessionJob = engineScope.launch {
            var server: com.airsonic.sender.streaming.LocalMediaHttpServer? = null
            var ctl: DlnaController? = null
            var endMsg: String? = null   // 电视端断开/播完时的收尾文案（优先级高于「已停止」）
            try {
                val src = ContentResolverRangeSource(app, uri, isVideo = isVideoFile)
                if (src.length <= 0) { fail(L10n.s.openFail, gen); return@launch }
                server = com.airsonic.sender.streaming.LocalMediaHttpServer(src)
                val port = withContext(Dispatchers.IO) { server.start() }; httpServer = server
                val localIp = localWifiIp() ?: run { fail("${L10n.s.castError}no ip", gen); return@launch }
                val url = "http://$localIp:$port${server.path}"
                val didl = buildDidl(device.name, url, src.mimeType, isVideoFile, sizeBytes = src.length)
                val c = DlnaController(controlUrl); ctl = c; dlnaCtl = c
                persistDlnaSession(app, controlUrl)
                if (!withContext(Dispatchers.IO) { c.setUri(url, didl) }) { fail("${L10n.s.castError}${c.lastError}", gen); return@launch }
                if (!withContext(Dispatchers.IO) { c.play() }) { fail("${L10n.s.castError}${c.lastError}", gen); return@launch }
                onCastingStarted(device.name)
                var soapFail = 0; var pollN = 0; var lastPosAtCheck = -1.0
                while (isActive && casting && gen == sessionGen) {
                    delay(1000)
                    val info = withContext(Dispatchers.IO) { c.getPositionInfo() }
                    if (info == null) {
                        // 连续 5s SOAP 无响应 ≈ 电视端已断（关机/退出播放器/断网）→ 收尾
                        if (++soapFail >= 5) { endMsg = L10n.s.tvDisconnected; break }
                        continue
                    }
                    soapFail = 0
                    videoPos.value = info.first; videoDur.value = info.second
                    // 每 5s 查一次传输状态：电视上按了停止/播放完 → 退出投送态，别留僵尸读秒
                    if (++pollN % 5 == 0) {
                        val st = withContext(Dispatchers.IO) { c.getTransportInfo() }
                        if (st == "STOPPED" || st == "NO_MEDIA_PRESENT") {
                            // 进度还在走 = 播放器状态误报（当贝缓冲态），别杀会话
                            if (lastPosAtCheck >= 0 && info.first > lastPosAtCheck + 0.5) {
                                // 活着，什么也不做
                            } else if (lastPosAtCheck >= 0) {
                                val done = info.second > 0 && info.first >= info.second - 2
                                endMsg = if (done) L10n.s.playFinished else L10n.s.tvDisconnected
                                break
                            }
                        }
                        lastPosAtCheck = info.first
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t   // 取消正常传播，别当错误
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                withContext(NonCancellable) { dlnaCleanup(gen, server, ctl, endMsg) }
            }
        }
    }

    /**
     * UPnP 实时音频：捕获 PCM → 实时流 → LiveAudioHttpServer → UPnP SetAVTransportURI+Play。
     * [sonos]=true：Sonos 电台管线（x-rincon-mp3radio:// + SoCo 同款电台 DIDL——新固件拒裸 http 电台 URI）；
     * [sonos]=false：通用 DLNA 渲染器（坚果等）——裸 http URL + 标准 audioBroadcast DIDL。
     * 任一模式开了 [sonosWav] 都改投无限长 WAV「超长曲目」（假大 Content-Length，swyh-rs 同款兜底）。
     */
    private fun startUpnpLiveAudioStream(app: Context, cc: SystemAudioCapture, device: AirDevice, gen: Int, sonos: Boolean = true) {
        val controlUrl = device.controlUrl ?: run { fail(L10n.s.setupFail); return }
        val gainCtl: GainVolumeController? =
            if (device.renderingControlUrl == null) GainVolumeController() else null
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
                ) { frame, _ -> server.push(frame) }
                encoder.start(); enc = encoder
            }

            // Sonos：电台管线（x-rincon-mp3radio + Rincon DIDL，新固件拒裸 http 电台 URI）。
            // 通用 DLNA（坚果等）：裸 http URL + 标准 audioBroadcast DIDL，不能用 Sonos 私有 scheme。
            val castUri = when {
                wav -> httpUrl
                sonos -> com.airsonic.sender.dlna.sonosRadioUri(httpUrl)
                else -> httpUrl
            }
            val didl = when {
                wav -> com.airsonic.sender.dlna.buildLiveWavDidl(device.name, httpUrl)
                sonos -> com.airsonic.sender.dlna.buildSonosRadioDidl(device.name)
                else -> com.airsonic.sender.dlna.buildLiveAudioDidl(device.name, httpUrl)
            }
            val c = DlnaController(controlUrl); ctl = c; dlnaCtl = c
            persistDlnaSession(app, controlUrl)
            if (!casting || gen != sessionGen) return
            val rcUrl = device.renderingControlUrl
            if (rcUrl != null) {
                bindVolume(UpnpVolumeController(RenderingControlController(rcUrl)), defaultPct = 50)
            } else {
                bindVolume(gainCtl!!, defaultPct = 100)
            }
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
                        updateMeters(pcm)
                        gainCtl?.let { scalePcm16(pcm, pcm.size, it.gain) }
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

    /**
     * 应用内屏幕镜像（DLNA 实时屏幕流）：录屏 H.264 → MPEG-TS → HTTP 直播 → SetAVTransportURI+Play。
     * 目标设备是坚果等 DLNA 渲染器（无 Miracast、AirPlay 未开时的自研路线）。
     * 时序沿用 Sonos 经验：编码器先跑、流产出数据后再 Play。延迟预期 1~3s（播放器缓冲）。
     */
    fun startScreenMirrorCast(context: Context, resultCode: Int, data: Intent) {
        val device = selected.value ?: run { statusLine.value = L10n.s.noDevice; phase.value = CastPhase.ERROR; return }
        if (device.type != DeviceType.DLNA || device.controlUrl == null) {
            statusLine.value = L10n.s.mirrorNeedsDlna; phase.value = CastPhase.ERROR; return
        }
        val app = context.applicationContext
        phase.value = CastPhase.CONNECTING
        statusLine.value = "${L10n.s.connecting} ${device.name} …"
        casting = true
        castLocksAcquire(app)
        val gen = ++sessionGen
        sessionJob?.cancel()
        CaptureProjectionService.start(app)
        sessionJob = engineScope.launch {
            var caster: com.airsonic.sender.screen.ScreenMirrorCaster? = null
            var projection: android.media.projection.MediaProjection? = null
            var server: com.airsonic.sender.streaming.LiveAudioHttpServer? = null
            var ctl: DlnaController? = null
            var endMsg: String? = null   // 电视端断开时的收尾文案（优先级高于「已停止」）
            var audioCap: SystemAudioCapture? = null
            var audioEnc: com.airsonic.sender.streaming.AacStreamEncoder? = null
            try {
                var waited = 0
                while (!CaptureProjectionService.isForeground && waited < 2000) { delay(50); waited += 50 }
                projection = withContext(Dispatchers.IO) {
                    val pm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    pm.getMediaProjection(resultCode, data)
                }
                // 无限长 TS 直播流（LiveAudioHttpServer 实为内容无关扇出：换 contentType 即可）
                // 新观众接入即补关键帧；队列按视频量级给 8192 包（≈1.5MB，音频默认 256 远不够）
                val srv = com.airsonic.sender.streaming.LiveAudioHttpServer(
                    contentType = "video/mp2t", pathExt = "ts", queueMax = 8192,
                    onSubscriber = { caster?.requestSyncFrame() })
                server = srv
                val port = withContext(Dispatchers.IO) { srv.start() }
                // 最长边压到 1920（1080p 级），保宽高比、偶数对齐（H.264 yuv420 要求偶数）
                val m = app.resources.displayMetrics
                val scale = minOf(1f, 1920f / maxOf(m.widthPixels, m.heightPixels))
                val w = ((m.widthPixels * scale).toInt() + 1) / 2 * 2
                val h = ((m.heightPixels * scale).toInt() + 1) / 2 * 2
                // 有 RECORD_AUDIO 才开音轨（用户在授权弹窗拒绝也能降级为纯画面镜像）
                val withAudio = androidx.core.content.ContextCompat.checkSelfPermission(
                    app, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val c = com.airsonic.sender.screen.ScreenMirrorCaster(
                    width = w, height = h, dpi = m.densityDpi, bitRate = 10_000_000,
                    withAudio = withAudio,
                    emit = { pkt -> srv.push(pkt) },   // 丢包自愈(drop-until-IDR)在 caster 内部
                    onLog = { android.util.Log.i("CastEngine", "mirror: $it") },
                )
                caster = c
                val proj = projection ?: run { fail(L10n.s.setupFail, gen); return@launch }
                // vivo 等 ROM 屏蔽 logcat → 失败原因必须透传到 UI 状态行
                if (!withContext(Dispatchers.IO) { c.start(proj) }) {
                    fail("${L10n.s.setupFail}: ${c.lastError ?: "?"}", gen); return@launch
                }
                // 等编码器产出 SPS/PPS（首帧配置），最多 2s
                var wr = 0; while (!c.ready && wr < 2000) { delay(50); wr += 50 }
                if (!c.ready) { fail("${L10n.s.setupFail}: 编码器无输出", gen); return@launch }
                // 声画同投：复用同一 MediaProjection 起 AudioPlaybackCapture → AAC → TS 音轨。
                // 捕获失败（目标 App 禁录/ROM 限制）不致命：降级纯画面，状态行能看到「无音轨」。
                var audioOn = false
                if (withAudio) {
                    runCatching {
                        val cap = SystemAudioCapture()
                        if (cap.start(proj)) {
                            val aenc = com.airsonic.sender.streaming.AacStreamEncoder(
                                sampleRate = 44100, channels = 2
                            ) { frame, pts -> c.writeAudioFrame(frame, pts) }
                            aenc.start()
                            audioCap = cap; audioEnc = aenc; audioOn = true
                            // 泵线程：readChunk 阻塞定速，AAC 帧随视频一起进 TS
                            thread(isDaemon = true, name = "airsonic-mirror-audio") {
                                try {
                                    while (casting && gen == sessionGen) {
                                        val pcm = cap.readChunk(4096) ?: break
                                        if (pcm.isNotEmpty()) aenc.encode(pcm)
                                    }
                                } catch (t: Throwable) {
                                    android.util.Log.w("CastEngine", "mirror audio pump exit: ${t.message}")
                                }
                            }
                        } else android.util.Log.w("CastEngine", "mirror: AudioPlaybackCapture 启动失败，纯画面降级")
                    }.onFailure { android.util.Log.w("CastEngine", "mirror audio init: ${it.message}") }
                }
                val localIp = localIpForTarget(device.host)
                    ?: run { fail("${L10n.s.castError}no ip", gen); return@launch }
                val url = "http://$localIp:$port${srv.path}"
                val didl = buildDidl(device.name, url, "video/mp2t", isVideo = true,
                    contentFeatures = com.airsonic.sender.dlna.DLNA_CONTENT_FEATURES_LIVE)   // OP=00 直播：少建缓冲降延迟
                val dc = DlnaController(device.controlUrl!!); ctl = dc; dlnaCtl = dc   // 入口已判非空
                persistDlnaSession(app, device.controlUrl!!)
                device.renderingControlUrl?.let {
                    bindVolume(UpnpVolumeController(RenderingControlController(it)), defaultPct = 50)
                }
                val fmtLabel = "H.264${if (audioOn) "+AAC" else ""}/TS"
                activeCodec.value = "$fmtLabel ${w}x${h}｜投…｜流=$localIp:$port"
                delay(500)   // 让编码流先产数据，渲染器一连即有内容（Sonos 同款时序经验）
                if (!withContext(Dispatchers.IO) { dc.setUri(url, didl) }) {
                    fail("${L10n.s.castError}${dc.lastError}", gen); return@launch
                }
                if (!withContext(Dispatchers.IO) { dc.play() }) {
                    fail("${L10n.s.castError}${dc.lastError}", gen); return@launch
                }
                if (!isActive || !casting || gen != sessionGen) return@launch
                onCastingStarted(device.name)
                // 诊断轮询（阻塞 SOAP 切 IO）：状态 + 拉流连接数 + 累计丢包（丢>0=下行拥塞）
                // 活着的第一判据是「电视还在拉流」(connections>0)——当贝缓冲直播流时传输状态会
                // 停在 STOPPED 误报（v0.3.7 曾因此刚投上就误判断开），状态只能当辅证：
                // 无拉流 且 (曾断订阅/状态STOPPED) 持续 12s 才判电视端断开。
                var hadSub = false; var gonePolls = 0
                while (isActive && casting && gen == sessionGen) {
                    delay(3000)
                    val st = withContext(Dispatchers.IO) { dc.getTransportInfo() }
                    activeCodec.value = "$fmtLabel ${w}x${h}｜S:${st ?: "?"}｜流x${srv.connections}｜丢${srv.drops}"
                    if (srv.connections > 0) { hadSub = true; gonePolls = 0; continue }
                    val stopped = st == "STOPPED" || st == "NO_MEDIA_PRESENT"
                    gonePolls = if (hadSub || stopped) gonePolls + 1 else 0
                    if (gonePolls >= 4) { endMsg = L10n.s.tvDisconnected; break }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                fail("${L10n.s.castError}${t.message}", gen)
            } finally {
                withContext(NonCancellable) {
                    runCatching { audioCap?.stop() }
                    runCatching { audioEnc?.stop() }
                    runCatching { caster?.stop() }
                    runCatching { projection?.stop() }   // 否则系统投屏指示常驻、干扰下次授权
                    runCatching { server?.stop() }
                    // SOAP Stop 是阻塞网络请求 → 切线程，别拖 UI 复位
                    ctl?.let { c2 -> thread(isDaemon = true, name = "airsonic-mirror-stop") { runCatching { c2.stop() } } }
                    if (dlnaCtl === ctl) dlnaCtl = null
                    if (gen == sessionGen) {
                        casting = false
                        castLocksRelease()
                        unbindVolume()
                        runCatching { CaptureProjectionService.stop(app) }
                        startedAt.value = 0L; level.value = 0f
                        spectrum.value = FloatArray(SPECTRUM_BANDS); castingDeviceName.value = ""
                        if (phase.value != CastPhase.ERROR) { phase.value = CastPhase.IDLE; statusLine.value = endMsg ?: L10n.s.stopped }
                    }
                }
            }
        }
    }

    private fun dlnaCleanup(
        gen: Int,
        ownServer: com.airsonic.sender.streaming.LocalMediaHttpServer? = null,
        ownCtl: DlnaController? = null,
        endMsg: String? = null,
    ) {
        // 自己的资源无条件关；网络 Stop 切后台(阻塞 SOAP 会拖慢 UI 复位好几秒)
        ownCtl?.let { c -> thread(isDaemon = true, name = "airsonic-dlna-stop") { runCatching { c.stop() } } }
        runCatching { ownServer?.stop() }
        if (gen != sessionGen) return        // 已被新会话接管：别动全局引用/UI 状态
        casting = false
        castLocksRelease()
        unbindVolume()
        if (dlnaCtl === ownCtl) dlnaCtl = null
        if (httpServer === ownServer) httpServer = null
        isVideo.value = false; videoPos.value = 0.0; videoDur.value = 0.0
        startedAt.value = 0L; level.value = 0f; spectrum.value = FloatArray(SPECTRUM_BANDS); castingDeviceName.value = ""
        if (phase.value != CastPhase.ERROR) { phase.value = CastPhase.IDLE; statusLine.value = endMsg ?: L10n.s.stopped }
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

    /** 绑定音量后端并按 getVolume 初始化滑块（读不到走默认）。在投送会话建立后调用。 */
    private fun bindVolume(controller: VolumeController, defaultPct: Int) {
        volumeController = controller
        volumeWarn.value = false
        val cur = controller.getVolume()
        val pct = (cur ?: defaultPct).coerceIn(0, 100)
        volumePct.value = pct
        muted.value = false
        volumeActive.value = true
    }

    private fun unbindVolume() {
        volumeThrottleJob?.cancel(); volumeThrottleJob = null
        pendingVolumePct = -1
        volumeController = null
        volumeActive.value = false
        muted.value = false
    }

    /** UI 调用：立即更新滑块状态，节流下发到后端。 */
    fun setVolume(pct: Int) {
        val v = pct.coerceIn(0, 100)
        volumePct.value = v
        if (v > 0 && muted.value) muted.value = false
        pendingVolumePct = v
        if (volumeThrottleJob?.isActive == true) return
        volumeThrottleJob = engineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val target = pendingVolumePct
                if (target < 0) break
                pendingVolumePct = -1
                // 下发结果反哺 UI：设备拒收(海信 VIDAA 等) → 滑块下提示改用遥控器
                volumeWarn.value = volumeController?.setVolume(target) == false
                delay(150)                       // 最后值胜出窗口
                if (pendingVolumePct < 0) break  // 窗口内无新值 → 收尾
            }
        }
    }

    /** UI 调用：切换静音。 */
    fun toggleMute() {
        val next = !muted.value
        muted.value = next
        engineScope.launch(Dispatchers.IO) { volumeController?.setMute(next) }
    }

    fun stop() {
        casting = false
        castLocksRelease()
        unbindVolume()
        sessionJob?.cancel()   // 协程路径：结构化取消，finally 在 NonCancellable 里清理
        cancelPin()            // 解开正在阻塞的 PIN 等待(poll 120s)，否则停止后会话挂着等 PIN
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
        castLocksRelease()
        unbindVolume()
        capture = null
        restorePhone(app)
        runCatching { CaptureProjectionService.stop(app) }
        startedAt.value = 0L
        level.value = 0f
        spectrum.value = FloatArray(SPECTRUM_BANDS)
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
        castLocksRelease()
        unbindVolume()
        if (videoCtl === ownCtl) videoCtl = null
        if (httpServer === ownServer) httpServer = null
        isVideo.value = false; videoPos.value = 0.0; videoDur.value = 0.0
        startedAt.value = 0L; level.value = 0f; spectrum.value = FloatArray(SPECTRUM_BANDS); castingDeviceName.value = ""
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