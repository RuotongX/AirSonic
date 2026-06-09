// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/** 本机媒体库（MediaStore）查询，供 app 内媒体浏览器（替代系统选择器）。 */
object MediaLibrary {

    data class Item(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val width: Int = 0,
        val height: Int = 0,
        val isVideo: Boolean = false,
    ) {
        val durationText: String
            get() {
                val s = (durationMs / 1000)
                val m = s / 60; val sec = s % 60
                return "%d:%02d".format(m, sec)
            }
        val resText: String get() = if (width > 0 && height > 0) "${width}x${height}" else ""
    }

    fun queryAudio(context: Context): List<Item> {
        val out = ArrayList<Item>()
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 10000"
        context.contentResolver.query(col, proj, sel, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idC)
                out.add(Item(ContentUris.withAppendedId(col, id), c.getString(nameC) ?: "未知", c.getLong(durC), isVideo = false))
            }
        }
        return out
    }

    fun queryVideo(context: Context): List<Item> {
        val out = ArrayList<Item>()
        val col = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        context.contentResolver.query(col, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameC = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durC = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val wC = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hC = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(idC)
                out.add(Item(ContentUris.withAppendedId(col, id), c.getString(nameC) ?: "未知",
                    c.getLong(durC), c.getInt(wC), c.getInt(hC), isVideo = true))
            }
        }
        return out
    }

    /** 缩略图（视频帧 / 音频封面）。失败返回 null。 */
    fun thumbnail(context: Context, item: Item): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(item.uri, Size(160, 160), null)
        } else null
    }.getOrNull()
}