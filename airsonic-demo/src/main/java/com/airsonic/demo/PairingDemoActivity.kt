// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airsonic.demo.capture.CaptureProjectionService
import com.airsonic.demo.databinding.ActivityPairingDemoBinding
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.capture.SystemAudioCapture
import com.airsonic.sender.discovery.AirplayDiscovery
import com.airsonic.sender.pairing.PairingHandshake
import com.airsonic.sender.streaming.AirplayStreamSession
import com.airsonic.sender.streaming.LoopbackAudioCast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 验证点 1 的演示界面：
 *  - mDNS 发现 _airplay._tcp 设备
 *  - 点击设备 / 手动输入 host:port 触发 pair-verify 握手框架
 *  - 实时打印分步日志，便于在真机 / airplay2-receiver 上对齐
 */
class PairingDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingDemoBinding
    private lateinit var discovery: AirplayDiscovery
    private val found = LinkedHashMap<String, AirDevice>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var pickedAudio: Uri? = null

    // ---- 系统音频捕获并投送（真实音乐链路）----
    private lateinit var projectionManager: MediaProjectionManager
    @Volatile private var casting = false
    private var capture: SystemAudioCapture? = null
    private var castHost = ""
    private var castPort = 7000
    private var castPin = "3939"

    private val recordPermForCast =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestProjectionForCast()
            else log("未授予录音权限，无法捕获系统音频")
        }
    private val projectionForCast =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null)
                startCaptureCast(result.resultCode, result.data!!)
            else log("用户取消 MediaProjection 授权")
        }

    private val audioPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedAudio = uri
            binding.txtPickedAudio.text = "已选: ${uri.lastPathSegment ?: uri}"
            log("选择音频: $uri")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.txtLog.movementMethod = ScrollingMovementMethod()

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        discovery = AirplayDiscovery(this)

        binding.btnDiscover.setOnClickListener { startDiscovery() }
        binding.btnStopDiscover.setOnClickListener {
            discovery.stop(); log("已停止发现")
        }
        binding.btnManualPair.setOnClickListener {
            val text = binding.inputManualHost.text.toString().trim()
            val parts = text.split(":")
            if (parts.size == 2) {
                val host = parts[0]
                val port = parts[1].toIntOrNull() ?: 7000
                pairVerify(host, port)
            } else {
                log("格式应为 host:port")
            }
        }
        binding.btnPairSetup.setOnClickListener {
            val text = binding.inputManualHost.text.toString().trim()
            val parts = text.split(":")
            if (parts.size == 2) {
                val host = parts[0]
                val port = parts[1].toIntOrNull() ?: 7000
                val pin = binding.inputPin.text.toString().trim().ifEmpty { "3939" }
                pairSetup(host, port, pin)
            } else {
                log("格式应为 host:port")
            }
        }
        binding.btnPickAudio.setOnClickListener {
            audioPicker.launch(arrayOf("audio/*"))
        }
        binding.btnPairAndPlay.setOnClickListener {
            val text = binding.inputManualHost.text.toString().trim()
            val parts = text.split(":")
            if (parts.size != 2) {
                log("格式应为 host:port"); return@setOnClickListener
            }
            if (pickedAudio == null) {
                log("请先选择音频文件"); return@setOnClickListener
            }
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: 7000
            val pin = binding.inputPin.text.toString().trim().ifEmpty { "3939" }
            pairAndPlay(host, port, pin, pickedAudio!!)
        }
        binding.btnLoopback.setOnClickListener {
            val text = binding.inputLoopback.text.toString().trim()
            val parts = text.split(":")
            if (parts.size != 2) {
                log("本地闭环格式应为 host:port（如 192.168.1.5:6010）"); return@setOnClickListener
            }
            if (pickedAudio == null) {
                log("请先选择音频文件"); return@setOnClickListener
            }
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: 6010
            loopback(host, port, pickedAudio!!)
        }
        binding.btnCaptureCast.setOnClickListener { toggleCaptureCast() }
    }

    /** 切换"捕获系统音频并投送"：首次点击开始（申请权限→授权→配对→实时推流），再次点击停止。 */
    private fun toggleCaptureCast() {
        if (casting) {
            log("⏹ 停止捕获投送")
            casting = false
            capture?.stop()
            binding.btnCaptureCast.text = "🎵 捕获系统音频并投送（开始/停止）"
            return
        }
        val parts = binding.inputManualHost.text.toString().trim().split(":")
        if (parts.size != 2) { log("格式应为 host:port"); return }
        castHost = parts[0]
        castPort = parts[1].toIntOrNull() ?: 7000
        castPin = binding.inputPin.text.toString().trim().ifEmpty { "3939" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { log("AudioPlaybackCapture 需 Android 10+"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) requestProjectionForCast()
        else recordPermForCast.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestProjectionForCast() {
        log("请求 MediaProjection 授权（用于系统音频捕获）...")
        projectionForCast.launch(projectionManager.createScreenCaptureIntent())
    }

    /** 授权成功：起前台 Service + 捕获 → 配对 + SETUP → streamCapturedPcm 实时推流到设备。 */
    private fun startCaptureCast(resultCode: Int, data: Intent) {
        CaptureProjectionService.start(this)
        log("──── 捕获并投送 -> $castHost:$castPort ────")
        log("前台 Service 已启动，请在汽水音乐/系统音乐等 App 播放音乐")
        casting = true
        binding.btnCaptureCast.text = "⏹ 停止投送"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                var cap: SystemAudioCapture? = null
                try {
                    var waited = 0
                    while (!CaptureProjectionService.isForeground && waited < 2000) {
                        Thread.sleep(50); waited += 50
                    }
                    val projection = projectionManager.getMediaProjection(resultCode, data)
                    val cc = SystemAudioCapture()
                    cap = cc
                    if (!cc.start(projection)) {
                        runOnUiThread { log("  ✗ AudioRecord 初始化失败（机型可能限制 PlaybackCapture）") }
                        return@withContext
                    }
                    capture = cc
                    val handshake = PairingHandshake(castHost, castPort)
                    val paired = handshake.pairSetup(castPin, onStep = { stepLog(it) }, transient = true)
                    if (!paired) { runOnUiThread { log("  ✗ 配对失败") }; return@withContext }
                    val session = AirplayStreamSession(castHost, handshake)
                    val result = session.setup { setupLog(it) }
                    if (result == null) { runOnUiThread { log("  ✗ SETUP 失败") }; return@withContext }
                    runOnUiThread { log("  ✓ dataPort=${result.dataPort}，开始实时捕获推流（播放音乐试试，再点按钮停止）") }
                    // 诊断：把捕获块峰值写到外部文件，adb pull 即可看捕获是否为静音（不打扰前台音乐 App）
                    val peakFile = java.io.File(getExternalFilesDir(null), "cap_peak.txt")
                    runCatching { peakFile.writeText("") }
                    var capN = 0
                    session.streamCapturedPcm(
                        result = result,
                        channels = 2,
                        isCancelled = { !casting },
                        nextChunk = {
                            val c = cc.readChunk(4096)
                            if (c != null && c.isNotEmpty()) {
                                capN++
                                if (capN % 50 == 0) {
                                    var pk = 0; var i = 0
                                    while (i + 1 < c.size) {
                                        val s = ((c[i + 1].toInt() shl 8) or (c[i].toInt() and 0xFF)).toShort().toInt()
                                        val a = if (s < 0) -s else s; if (a > pk) pk = a; i += 2
                                    }
                                    runCatching { peakFile.appendText("cap#$capN peak=$pk/32767 size=${c.size}\n") }
                                }
                            }
                            c
                        }
                    ) { setupLog(it) }
                } catch (t: Throwable) {
                    runOnUiThread { log("  ✗ 捕获投送异常: ${t.message}") }
                } finally {
                    casting = false
                    runCatching { cap?.stop() }
                    capture = null
                    runOnUiThread {
                        CaptureProjectionService.stop(this@PairingDemoActivity)
                        binding.btnCaptureCast.text = "🎵 捕获系统音频并投送（开始/停止）"
                        log("  · 捕获投送已结束")
                    }
                }
            }
        }
    }

    private fun stepLog(step: PairingHandshake.Step) = runOnUiThread {
        log(when (step) {
            is PairingHandshake.Step.Info -> "  · ${step.message}"
            is PairingHandshake.Step.Success -> "  ✓ ${step.message}"
            is PairingHandshake.Step.Failure -> "  ✗ ${step.message}"
        })
    }

    private fun setupLog(step: AirplayStreamSession.Step) = runOnUiThread {
        log(when (step) {
            is AirplayStreamSession.Step.Info -> "  · ${step.message}"
            is AirplayStreamSession.Step.Success -> "  ✓ ${step.message}"
            is AirplayStreamSession.Step.Failure -> "  ✗ ${step.message}"
        })
    }

    private fun startDiscovery() {
        log("开始发现 _airplay._tcp ...")
        found.clear()
        binding.listDevices.removeAllViews()
        discovery.start(object : DeviceListener {
            override fun onDeviceFound(device: AirDevice) {
                runOnUiThread {
                    if (found.put(device.id, device) == null) {
                        addDeviceRow(device)
                        log("发现: ${device.name} @ ${device.id} type=${device.type} photo=${device.capabilities.supportsPhoto}")
                    }
                }
            }

            override fun onDeviceLost(device: AirDevice) {
                runOnUiThread { log("丢失: ${device.name}") }
            }

            override fun onDiscoveryFailed(reason: String) {
                runOnUiThread { log("发现失败: $reason") }
            }
        })
    }

    private fun addDeviceRow(device: AirDevice) {
        val btn = Button(this).apply {
            text = "${device.name}\n${device.id}  [${device.type}]"
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { pairVerify(device.host, device.port) }
        }
        binding.listDevices.addView(btn)
    }

    private fun pairVerify(host: String, port: Int) {
        log("──── pair-verify -> $host:$port ────")
        lifecycleScope.launch {
            val handshake = PairingHandshake(host, port)
            withContext(Dispatchers.IO) {
                handshake.pairVerify { step ->
                    val msg = when (step) {
                        is PairingHandshake.Step.Info -> "  · ${step.message}"
                        is PairingHandshake.Step.Success -> "  ✓ ${step.message}"
                        is PairingHandshake.Step.Failure -> "  ✗ ${step.message}"
                    }
                    runOnUiThread { log(msg) }
                }
            }
        }
    }

    private fun pairSetup(host: String, port: Int, pin: String) {
        // 真设备（HomePod/AppleTV）走 transient 配对：固定密码 3939、M1 带 flags、需 X-Apple-HKP 头。
        log("──── pair-setup (transient, PIN=$pin) -> $host:$port ────")
        lifecycleScope.launch {
            val handshake = PairingHandshake(host, port)
            withContext(Dispatchers.IO) {
                val paired = handshake.pairSetup(pin, onStep = { step ->
                    val msg = when (step) {
                        is PairingHandshake.Step.Info -> "  · ${step.message}"
                        is PairingHandshake.Step.Success -> "  ✓ ${step.message}"
                        is PairingHandshake.Step.Failure -> "  ✗ ${step.message}"
                    }
                    runOnUiThread { log(msg) }
                }, transient = true)

                if (paired) {
                    runOnUiThread { log("──── 配对成功，继续 SETUP（加密 RTSP）────") }
                    val session = AirplayStreamSession(host, handshake)
                    val result = session.setup { step ->
                        val msg = when (step) {
                            is AirplayStreamSession.Step.Info -> "  · ${step.message}"
                            is AirplayStreamSession.Step.Success -> "  ✓ ${step.message}"
                            is AirplayStreamSession.Step.Failure -> "  ✗ ${step.message}"
                        }
                        runOnUiThread { log(msg) }
                    }
                    if (result != null) {
                        runOnUiThread {
                            log("  ✓ 全链路就绪：dataPort=${result.dataPort}, controlPort=${result.controlPort}")
                            log("  ⏭ 下一步：往 dataPort 推 ALAC 加密 RTP 出声")
                        }
                        session.record { step ->
                            val msg = when (step) {
                                is AirplayStreamSession.Step.Info -> "  · ${step.message}"
                                is AirplayStreamSession.Step.Success -> "  ✓ ${step.message}"
                                is AirplayStreamSession.Step.Failure -> "  ✗ ${step.message}"
                            }
                            runOnUiThread { log(msg) }
                        }
                    }
                }
            }
        }
    }

    /**
     * 配对 → SETUP → streamAudio：真设备端到端出声路径。
     * RECORD 与音频推流由 [AirplayStreamSession.streamAudio] 内部完成。
     */
    private fun pairAndPlay(host: String, port: Int, pin: String, audioUri: Uri) {
        log("──── 配对并出声 (PIN=$pin) -> $host:$port ────")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                var pfd: ParcelFileDescriptor? = null
                try {
                    val handshake = PairingHandshake(host, port)
                    val paired = handshake.pairSetup(pin, onStep = { step ->
                        val msg = when (step) {
                            is PairingHandshake.Step.Info -> "  · ${step.message}"
                            is PairingHandshake.Step.Success -> "  ✓ ${step.message}"
                            is PairingHandshake.Step.Failure -> "  ✗ ${step.message}"
                        }
                        runOnUiThread { log(msg) }
                    }, transient = true)
                    if (!paired) {
                        runOnUiThread { log("  ✗ 配对失败，终止") }
                        return@withContext
                    }
                    runOnUiThread { log("──── 配对成功，SETUP（加密 RTSP）────") }
                    val session = AirplayStreamSession(host, handshake)
                    val result = session.setup { step ->
                        val msg = when (step) {
                            is AirplayStreamSession.Step.Info -> "  · ${step.message}"
                            is AirplayStreamSession.Step.Success -> "  ✓ ${step.message}"
                            is AirplayStreamSession.Step.Failure -> "  ✗ ${step.message}"
                        }
                        runOnUiThread { log(msg) }
                    }
                    if (result == null) {
                        runOnUiThread { log("  ✗ SETUP 失败，终止") }
                        return@withContext
                    }
                    runOnUiThread { log("  ✓ dataPort=${result.dataPort}，开始 RECORD + 音频推流") }

                    pfd = contentResolver.openFileDescriptor(audioUri, "r")
                    if (pfd == null) {
                        runOnUiThread { log("  ✗ 无法打开音频文件") }
                        return@withContext
                    }
                    session.streamAudio(result, pfd.fileDescriptor) { step ->
                        val msg = when (step) {
                            is AirplayStreamSession.Step.Info -> "  · ${step.message}"
                            is AirplayStreamSession.Step.Success -> "  ✓ ${step.message}"
                            is AirplayStreamSession.Step.Failure -> "  ✗ ${step.message}"
                        }
                        runOnUiThread { log(msg) }
                    }
                } catch (t: Throwable) {
                    runOnUiThread { log("  ✗ 异常: ${t.message}") }
                } finally {
                    runCatching { pfd?.close() }
                }
            }
        }
    }

    /**
     * 本地闭环：跳过配对/SETUP，用固定 key 把 解码→ALAC→加密→UDP 推到电脑。
     * 电脑端 recon/loopback_recv.py 解密落盘 WAV，验证新管道字节级正确。
     */
    private fun loopback(host: String, port: Int, audioUri: Uri) {
        log("──── 本地闭环 -> $host:$port ────")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                var pfd: ParcelFileDescriptor? = null
                try {
                    pfd = contentResolver.openFileDescriptor(audioUri, "r")
                    if (pfd == null) {
                        runOnUiThread { log("  ✗ 无法打开音频文件") }
                        return@withContext
                    }
                    LoopbackAudioCast(host, port).cast(pfd.fileDescriptor) { ev ->
                        val msg = when (ev) {
                            is LoopbackAudioCast.Event.Info -> "  · ${ev.message}"
                            is LoopbackAudioCast.Event.Success -> "  ✓ ${ev.message}"
                            is LoopbackAudioCast.Event.Failure -> "  ✗ ${ev.message}"
                        }
                        runOnUiThread { log(msg) }
                    }
                } catch (t: Throwable) {
                    runOnUiThread { log("  ✗ 异常: ${t.message}") }
                } finally {
                    runCatching { pfd?.close() }
                }
            }
        }
    }

    private fun log(line: String) {
        val ts = timeFmt.format(Date())
        binding.txtLog.append("[$ts] $line\n")
        // 滚到底部
        val scroll = binding.logScroll
        scroll.post { scroll.fullScroll(TextView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { discovery.stop() }
    }
}