package com.airsonic.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airsonic.demo.capture.CaptureProjectionService
import com.airsonic.demo.databinding.ActivityCaptureDemoBinding
import com.airsonic.sender.capture.SystemAudioCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 验证点 2 演示：
 *  1) 申请 RECORD_AUDIO 权限
 *  2) 申请 MediaProjection 授权
 *  3) 启动前台 Service + AudioPlaybackCapture 采集 5 秒
 *  4) 输出统计（峰值/非静音帧比例），判定是否捕获成功 → 填兼容性表
 */
class CaptureDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureDemoBinding
    private lateinit var projectionManager: MediaProjectionManager
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val recordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestProjection()
            else log("未授予录音权限，无法采集")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startCaptureFlow(result.resultCode, result.data!!)
            } else {
                log("用户取消 MediaProjection 授权")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnCapture.setOnClickListener { ensurePermissionThenCapture() }
    }

    private fun ensurePermissionThenCapture() {
        binding.txtResult.text = ""
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestProjection()
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestProjection() {
        log("请求 MediaProjection 授权 ...")
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureFlow(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log("AudioPlaybackCapture 需要 Android 10 (API 29) 及以上")
            return
        }
        // 前台 Service 拉起，满足 MediaProjection 运行约束
        CaptureProjectionService.start(this)
        log("前台 Service 已启动，开始采集 5 秒（请确保有音乐正在播放）")

        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                try {
                    // 等待前台 Service 真正进入前台（Android 14+ 要求 getMediaProjection 前已有
                    // mediaProjection 类型的前台 Service 在运行，否则抛 SecurityException）。
                    var waited = 0
                    while (!CaptureProjectionService.isForeground && waited < 2000) {
                        Thread.sleep(50)
                        waited += 50
                    }
                    val projection = projectionManager.getMediaProjection(resultCode, data)
                    val capture = SystemAudioCapture()
                    if (!capture.start(projection)) {
                        return@withContext null
                    }
                    val s = capture.sampleForStats(5000)
                    capture.stop()
                    projection.stop()
                    s
                } catch (t: Throwable) {
                    runOnUiThread { log("✗ 采集异常: ${t.javaClass.simpleName}: ${t.message}") }
                    null
                }
            }
            CaptureProjectionService.stop(this@CaptureDemoActivity)

            if (stats == null) {
                log("✗ AudioRecord 初始化失败 / 采集未完成（机型可能限制 PlaybackCapture）")
                return@launch
            }
            renderStats(stats)
        }
    }

    private fun renderStats(s: SystemAudioCapture.CaptureStats) {
        val verdict = if (s.isLikelyCapturing) "✓ 捕获成功（非静音）" else "✗ 仅静音 / 被拒绝"
        val ratio = if (s.totalFrames > 0)
            (s.nonSilentFrames * 100.0 / s.totalFrames) else 0.0
        log("──── 采集结果 ────")
        log("总帧数:        ${s.totalFrames}")
        log("总字节:        ${s.totalBytes}")
        log("非静音帧:      ${s.nonSilentFrames} (${"%.1f".format(ratio)}%)")
        log("峰值振幅:      ${s.peakAmplitude} / 32767")
        log("判定:          $verdict")
        log("")
        log("➡ 请把当前音乐 App 名称 + 上述判定填入兼容性表")
    }

    private fun log(line: String) {
        val ts = timeFmt.format(Date())
        binding.txtResult.append("[$ts] $line\n")
    }
}
