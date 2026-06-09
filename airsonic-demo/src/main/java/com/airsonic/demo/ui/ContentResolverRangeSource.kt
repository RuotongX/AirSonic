// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.content.Context
import android.net.Uri
import com.airsonic.sender.streaming.RangeSource
import java.io.FileInputStream
import java.io.InputStream

/** 用 ContentResolver 把 content:// 暴露为 RangeSource（按 offset 重新打开 + skip）。 */
class ContentResolverRangeSource(
    private val context: Context,
    private val uri: Uri,
) : RangeSource {
    override val length: Long by lazy {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
        }.getOrDefault(-1L)
    }
    override val mimeType: String by lazy {
        context.contentResolver.getType(uri) ?: "video/mp4"
    }
    override fun open(offset: Long): InputStream {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
        val fis = FileInputStream(pfd.fileDescriptor)
        var toSkip = offset
        while (toSkip > 0) { val s = fis.skip(toSkip); if (s <= 0) break; toSkip -= s }
        return fis
    }
}