package com.airsonic.demo.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.airsonic.sender.api.AirDevice

/**
 * 每设备的本地持久化设置：自定义显示名、永久隐藏。
 * 以 mDNS 设备名为稳定 key（IP 变化不影响）。[version] 供 Compose 重组。
 */
object DevicePrefs {
    private const val PREF = "airsonic_device_prefs"

    /** 写入即自增，触发依赖此值的 Composable 重组。 */
    val version = mutableStateOf(0)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(device: AirDevice) = device.name

    fun customName(ctx: Context, device: AirDevice): String? =
        sp(ctx).getString("name_${key(device)}", null)?.trim()?.ifBlank { null }

    fun setCustomName(ctx: Context, device: AirDevice, name: String?) {
        sp(ctx).edit().apply {
            if (name.isNullOrBlank()) remove("name_${key(device)}")
            else putString("name_${key(device)}", name.trim())
        }.apply()
        version.value++
    }

    /** 显示名：自定义名优先，否则原始 mDNS 名。 */
    fun displayName(ctx: Context, device: AirDevice): String =
        customName(ctx, device) ?: device.name

    fun isHidden(ctx: Context, device: AirDevice): Boolean =
        sp(ctx).getBoolean("hidden_${key(device)}", false)

    fun setHidden(ctx: Context, device: AirDevice, hidden: Boolean) {
        sp(ctx).edit().putBoolean("hidden_${key(device)}", hidden).apply()
        version.value++
    }
}
