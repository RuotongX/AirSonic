// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.dlna

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RenderingControlTest {
    // 公网 IP → pinLanUrl 拒绝 → 不发网络，命令必败/读回 null（验证钉址防护在音量路径也生效）。
    private val ctl = RenderingControlController("http://8.8.8.8:1400/ctl/RC")

    @Test fun setVolumeRejectsNonLanUrl() { assertFalse(ctl.setVolume(50)) }
    @Test fun getVolumeNullOnNonLanUrl() { assertNull(ctl.getVolume()) }
    @Test fun setMuteRejectsNonLanUrl() { assertFalse(ctl.setMute(true)) }
}
