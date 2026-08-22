// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.airsonic.demo.MainActivity
import com.airsonic.demo.ui.theme.AirSonicTheme
import com.airsonic.demo.ui.theme.AuroraBackground

/** 产品 UI 入口（Compose / Aurora）：托管导航 + 桥接系统能力（投屏授权/文件选择）。 */
class StudioActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val recordPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchProjection()
            else {
                CastEngine.phase.value = CastPhase.ERROR
                CastEngine.statusLine.value = if (L10n.lang.value == Lang.EN) "Microphone permission denied" else "未授予录音权限"
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CastEngine.startSystemAudioCast(this, result.resultCode, result.data!!)
            } else {
                CastEngine.statusLine.value = if (L10n.lang.value == Lang.EN) "Screen-capture canceled" else "已取消投屏授权"
            }
        }

    /** 应用内屏幕镜像（DLNA）：音轨需要 RECORD_AUDIO（拒绝则降级纯画面镜像）。 */
    private val mirrorRecordPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 不管是否授予都继续发起录屏授权；有无权限决定 TS 里带不带 AAC 音轨
            mirrorProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

    /** 应用内屏幕镜像（DLNA）：录屏+系统声音（AudioPlaybackCapture→AAC 音轨，声画同投）。 */
    private val mirrorProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CastEngine.startScreenMirrorCast(this, result.resultCode, result.data!!)
            } else {
                CastEngine.statusLine.value = if (L10n.lang.value == Lang.EN) "Screen-capture canceled" else "已取消投屏授权"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L10n.load(this)   // 载入已保存语言
        CastEngine.loadPrefs(this)   // 载入「强制 ALAC」等开关
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val actions = CastActions(
            requestSystemAudioCast = { ensurePermThenProjection() },
            requestScreenMirrorCast = {
                // 已有录音权限直接录屏；没有先补权限（拒绝也继续，降级纯画面）
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) mirrorProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                else mirrorRecordPerm.launch(Manifest.permission.RECORD_AUDIO)
            },
            openDebug = { startActivity(Intent(this, MainActivity::class.java)) },
        )

        setContent {
            AirSonicTheme {
                AuroraBackground {
                    AppNav(actions)
                }
            }
        }
    }

    private fun ensurePermThenProjection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) launchProjection()
        else recordPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { CastEngine.stopDiscovery() }
    }
}