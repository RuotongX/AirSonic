// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import org.junit.Assert.assertTrue
import org.junit.Test

class DidlTest {
    @Test fun videoDidlHasVideoClassAndProtocolInfo() {
        val didl = buildDidl(title = "My Movie", url = "http://1.2.3.4:9/v/x",
            mime = "video/mp4", isVideo = true)
        assertTrue(didl.contains("object.item.videoItem"))
        assertTrue(didl.contains("http-get:*:video/mp4:$DLNA_CONTENT_FEATURES"))
        assertTrue(didl.contains("<dc:title>My Movie</dc:title>"))
        assertTrue(didl.contains("http://1.2.3.4:9/v/x"))
    }

    @Test fun audioDidlHasMusicTrackClass() {
        val didl = buildDidl("Song", "http://h/a", "audio/mpeg", isVideo = false)
        assertTrue(didl.contains("object.item.audioItem.musicTrack"))
    }

    @Test fun titleAndUrlAreEscaped() {
        val didl = buildDidl("A & B", "http://h/x?a=1&b=2", "video/mp4", true)
        assertTrue(didl.contains("A &amp; B"))
        assertTrue(didl.contains("a=1&amp;b=2"))
    }
}