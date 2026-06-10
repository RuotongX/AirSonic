// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimeTypesTest {
    @Test fun extension_mapsCommonVideo() {
        assertEquals("video/mp4", mimeForExtension("movie.mp4"))
        assertEquals("video/mp4", mimeForExtension("clip.M4V"))          // 大小写不敏感
        assertEquals("video/x-matroska", mimeForExtension("show.mkv"))
        assertEquals("video/x-msvideo", mimeForExtension("old.avi"))
        assertEquals("video/quicktime", mimeForExtension("phone.mov"))
        assertEquals("video/webm", mimeForExtension("web.webm"))
        assertEquals("video/mp2t", mimeForExtension("stream.ts"))
    }

    @Test fun extension_mapsCommonAudio() {
        assertEquals("audio/mpeg", mimeForExtension("song.mp3"))
        assertEquals("audio/flac", mimeForExtension("hires.flac"))
        assertEquals("audio/wav", mimeForExtension("voice.wav"))
        assertEquals("audio/mp4", mimeForExtension("track.m4a"))
    }

    @Test fun extension_unknownOrMissing_null() {
        assertNull(mimeForExtension("noext"))
        assertNull(mimeForExtension("archive.zip"))
        assertNull(mimeForExtension(""))
    }

    @Test fun correct_keepsUsableReportedMime() {
        // ContentResolver 已给出具体的 video/* 或 audio/*，原样保留
        assertEquals("video/x-matroska",
            correctMediaMime("video/x-matroska", "show.bin", isVideo = true))
        assertEquals("audio/flac",
            correctMediaMime("audio/flac", "x", isVideo = false))
    }

    @Test fun correct_octetStream_derivesFromName() {
        // 经典坑：getType 给 application/octet-stream → 按扩展名纠正，否则严格电视拒播
        assertEquals("video/x-matroska",
            correctMediaMime("application/octet-stream", "show.mkv", isVideo = true))
        assertEquals("audio/flac",
            correctMediaMime("application/octet-stream", "hires.flac", isVideo = false))
    }

    @Test fun correct_nullReported_derivesFromName() {
        assertEquals("video/mp4", correctMediaMime(null, "a.mp4", isVideo = true))
    }

    @Test fun correct_fallsBackByKind_whenUnknown() {
        // 既无可用 reported、扩展名也认不出 → 按音视频类型兜底
        assertEquals("video/mp4", correctMediaMime(null, "mystery", isVideo = true))
        assertEquals("audio/mpeg", correctMediaMime("application/octet-stream", "mystery", isVideo = false))
    }

    @Test fun correct_genericApplicationMime_treatedAsUnusable() {
        // 个别 provider 返回 application/* 之外的怪 mime，但不是 audio/video → 仍按扩展名纠正
        assertEquals("video/mp4", correctMediaMime("application/mp4", "a.mp4", isVideo = true))
    }
}
