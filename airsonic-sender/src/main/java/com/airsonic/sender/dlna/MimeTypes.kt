// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

/**
 * DLNA mime 纠正（纯逻辑，JVM 可测）。
 *
 * 背景：Android `ContentResolver.getType()` 对 MKV/AVI、以及部分 SAF/下载来源的文件常返回
 * `application/octet-stream`（甚至 null）。若把它原样塞进 DIDL 的 `protocolInfo` 与
 * HTTP `Content-Type`，**严格渲染器（三星/索尼/LG 电视）会直接拒播**——DLNA「投了不播」最常见根因。
 * 此处在 mime 不可用时按文件扩展名纠正，最后按音/视频类型兜底。
 */

/** 文件名/扩展名 → 已知媒体 mime；认不出返回 null。 */
fun mimeForExtension(name: String): String? =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "3gp", "3gpp" -> "video/3gpp"
        "mpg", "mpeg" -> "video/mpeg"
        "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wma" -> "audio/x-ms-wma"
        else -> null
    }

/**
 * 纠正 [reported]（来自 ContentResolver）为渲染器可接受的 mime。
 * 优先保留可用的 video/ 或 audio/ 前缀 mime；否则按 [name] 扩展名推；再否则按 [isVideo] 兜底。
 */
fun correctMediaMime(reported: String?, name: String, isVideo: Boolean): String {
    val r = reported?.trim()
    val usable = r != null && r.isNotEmpty() &&
        (r.startsWith("video/", true) || r.startsWith("audio/", true))
    if (usable) return r!!
    return mimeForExtension(name) ?: if (isVideo) "video/mp4" else "audio/mpeg"
}
