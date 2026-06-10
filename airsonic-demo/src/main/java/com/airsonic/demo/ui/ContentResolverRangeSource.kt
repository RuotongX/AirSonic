// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.airsonic.sender.dlna.correctMediaMime
import com.airsonic.sender.streaming.RangeSource
import java.io.FileInputStream
import java.io.InputStream

/** 用 ContentResolver 把 content:// 暴露为 RangeSource（按 offset 重新打开 + skip）。 */
class ContentResolverRangeSource(
    private val context: Context,
    private val uri: Uri,
    private val isVideo: Boolean = true,
) : RangeSource {
    override val length: Long by lazy {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
        }.getOrDefault(-1L)
    }

    /** SAF 显示名（取扩展名用），失败回退到 uri 末段。 */
    private val displayName: String by lazy {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
    }

    /**
     * 纠正 mime：ContentResolver 对 MKV/AVI 等常给 application/octet-stream，
     * 严格电视会拒播，故按文件名扩展名纠正（见 [correctMediaMime]）。
     */
    override val mimeType: String by lazy {
        correctMediaMime(context.contentResolver.getType(uri), displayName, isVideo)
    }
    override fun open(offset: Long): InputStream {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
        val fis = FileInputStream(pfd.fileDescriptor)
        var toSkip = offset
        while (toSkip > 0) { val s = fis.skip(toSkip); if (s <= 0) break; toSkip -= s }
        return fis
    }
}