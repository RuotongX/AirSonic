package com.airsonic.demo

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airsonic.demo.databinding.ActivityFileCastDemoBinding
import com.airsonic.sender.streaming.FileCastSession
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.discovery.AirplayDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 验证点 3：本地文件直投 Demo。
 * 选择本地音频 → 配对 → RTSP/RTP 推流到接收端，接收端落盘 WAV 出声。
 */
class FileCastDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileCastDemoBinding
    private var pickedUri: Uri? = null
    private var discovery: AirplayDiscovery? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            binding.txtPicked.text = "已选择: ${uri.lastPathSegment ?: uri}"
            log("选择文件: $uri")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileCastDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickFile.setOnClickListener {
            picker.launch(arrayOf("audio/*"))
        }
        binding.btnDiscover.setOnClickListener { startDiscovery() }
        binding.btnCastVerify.setOnClickListener { startCast(usePin = false) }
        binding.btnCastSetup.setOnClickListener { startCast(usePin = true) }
        binding.btnCastEncrypted.setOnClickListener { startCast(usePin = false, encrypted = true) }
        binding.btnCastAlac.setOnClickListener { startCast(usePin = false, encrypted = true, useAlac = true) }
    }

    private fun startCast(usePin: Boolean, encrypted: Boolean = false, useAlac: Boolean = false) {
        val uri = pickedUri
        if (uri == null) {
            log("请先选择音频文件")
            return
        }
        val host = binding.inputHost.text.toString().trim().ifEmpty { "127.0.0.1" }
        val pairPort = binding.inputPairPort.text.toString().trim().toIntOrNull() ?: 7100
        val rtspPort = binding.inputRtspPort.text.toString().trim().toIntOrNull() ?: 7200
        val pin = if (usePin) "3939" else null

        log("──── 开始投送 (${if (useAlac) "加密+ALAC" else if (encrypted) "加密" else if (usePin) "PIN首配" else "pair-verify"}) ────")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                var pfd: ParcelFileDescriptor? = null
                try {
                    pfd = contentResolver.openFileDescriptor(uri, "r")
                    if (pfd == null) {
                        runOnUiThread { log("无法打开文件") }
                        return@withContext
                    }
                    val session = FileCastSession(host, pairPort, rtspPort)
                    session.cast(pfd.fileDescriptor, pin, encrypted, useAlac) { ev ->
                        val msg = when (ev) {
                            is FileCastSession.Event.Info -> ev.message
                            is FileCastSession.Event.Progress -> "   ...已推 ${ev.sentBytes / 1024}KB"
                            is FileCastSession.Event.Success -> "✓ ${ev.message}"
                            is FileCastSession.Event.Failure -> "✗ ${ev.message}"
                        }
                        runOnUiThread { log(msg) }
                    }
                } catch (t: Throwable) {
                    runOnUiThread { log("异常: ${t.message}") }
                } finally {
                    runCatching { pfd?.close() }
                }
            }
        }
    }

    private fun log(msg: String) {
        val t = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        binding.txtLog.append("[$t] $msg\n")
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun startDiscovery() {
        log("开始发现 _airplay._tcp 设备...")
        val d = AirplayDiscovery(this)
        discovery = d
        d.start(object : DeviceListener {
            override fun onDeviceFound(device: AirDevice) {
                runOnUiThread {
                    log("发现: ${device.name} @ ${device.host}:${device.port} [${device.type}]")
                    // 自动填入第一个有效 host:port（真设备配对端口通常 7000）
                    if (device.host.isNotEmpty()) {
                        binding.inputHost.setText(device.host)
                        binding.inputPairPort.setText(device.port.toString())
                        binding.inputRtspPort.setText(device.port.toString())
                        log("已自动填入 host=${device.host} port=${device.port}（真设备配对/RTSP 同端口）")
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

    override fun onDestroy() {
        super.onDestroy()
        runCatching { discovery?.stop() }
    }
}
