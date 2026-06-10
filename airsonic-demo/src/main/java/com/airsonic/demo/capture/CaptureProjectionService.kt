// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 承载 MediaProjection 的前台 Service。
 *
 * Android 上 [android.media.projection.MediaProjection] 必须在前台 Service
 * 运行期间使用（Android 10+ 强约束）。本 Service 仅负责把进程拉到前台并展示
 * 常驻通知；实际的捕获逻辑由 Activity 通过 [SystemAudioCapture] 驱动。
 */
class CaptureProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 进程被杀后系统以 null intent 重启时,CastEngine 单例状态已灭、没有投送在跑,
        // 再 startForeground 就成了停不掉的僵尸通知 → 直接 stopSelf。
        if (intent == null && !com.airsonic.demo.ui.CastEngine.isActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 10+ 要求承载 MediaProjection 的前台 Service 显式声明 mediaProjection 类型，
        // 否则 MediaProjection.start() 会抛 SecurityException。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        isForeground = true
        return START_NOT_STICKY   // 投送会话由 CastEngine 显式管理,不要系统自动重启
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirSonic 捕获",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirSonic 系统音频捕获中")
            .setContentText("正在采集系统音频用于兼容性测试")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "airsonic_capture"
        private const val NOTIF_ID = 1001

        /** Service 是否已进入前台，供 Activity 在调用 getMediaProjection() 前轮询等待。 */
        @Volatile
        var isForeground: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CaptureProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureProjectionService::class.java))
        }
    }
}