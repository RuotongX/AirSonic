// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.api

import com.airsonic.sender.dlna.RenderingControlController
import com.airsonic.sender.streaming.AirplayStreamSession

/** 投送音量后端统一抽象。pct 一律 0..100。 */
interface VolumeController {
    /** 下发音量 0..100，成功返回 true。 */
    fun setVolume(pct: Int): Boolean
    /** 读回当前音量 0..100；不支持/失败返回 null（UI 走默认值）。 */
    fun getVolume(): Int?
    /** 静音/取消静音（取消时恢复上次非静音音量），成功返回 true。 */
    fun setMute(muted: Boolean): Boolean
}

/**
 * pct(0..100) → AirPlay RAOP 音量 dB：
 * 0 或负 → -144.0（静音）；否则线性 -30..0（100→0、50→-15、1→-29.7）。
 */
fun pctToAirplayDb(pct: Int): Double =
    if (pct <= 0) -144.0 else -30.0 + pct.coerceAtMost(100) / 100.0 * 30.0

/** AirPlay：经 SET_PARAMETER volume 下发 dB；RAOP 音量读不可靠 → getVolume 返 null。 */
class AirplayVolumeController(private val session: AirplayStreamSession) : VolumeController {
    @Volatile private var lastPct: Int = 50
    override fun setVolume(pct: Int): Boolean {
            if (pct > 0) lastPct = pct.coerceAtMost(100)
            val ok = session.setVolume(pctToAirplayDb(pct)) {}
            android.util.Log.i("AirsonicVol", "setVolume($pct) -> db=${"%.1f".format(pctToAirplayDb(pct))} ok=$ok")
            return ok
        }
    override fun getVolume(): Int? = null
    override fun setMute(muted: Boolean): Boolean =
        session.setVolume(if (muted) -144.0 else pctToAirplayDb(lastPct)) {}
}

/** 通用 DLNA / Sonos：RenderingControl 真音量。 */
class UpnpVolumeController(private val ctl: RenderingControlController) : VolumeController {
    override fun setVolume(pct: Int): Boolean = ctl.setVolume(pct)
    override fun getVolume(): Int? = ctl.getVolume()
    override fun setMute(muted: Boolean): Boolean = ctl.setMute(muted)
}

/** 降级：无 RenderingControl 时，推流循环读 [gain] 给 PCM 乘增益。 */
class GainVolumeController : VolumeController {
    @Volatile var gain: Float = 1f
        private set
    @Volatile private var lastGain: Float = 1f
    override fun setVolume(pct: Int): Boolean {
        gain = pct.coerceIn(0, 100) / 100f
        if (gain > 0f) lastGain = gain
        return true
    }
    override fun getVolume(): Int? = (gain * 100).toInt()
    override fun setMute(muted: Boolean): Boolean {
        gain = if (muted) 0f else lastGain
        return true
    }
}
