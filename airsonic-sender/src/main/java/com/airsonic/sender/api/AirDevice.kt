package com.airsonic.sender.api

/**
 * 接收设备能力位。
 */
data class DeviceCapabilities(
    val supportsAudio: Boolean = true,
    val supportsPhoto: Boolean = false,
    /** 是否支持视频播放/镜像（有屏设备：Apple TV / Mac / AirPlay2 TV）。 */
    val supportsVideo: Boolean = false,
    /** AirPlay 协议主版本：1 或 2。 */
    val airplayVersion: Int = 2,
    /** 是否要求加密配对（AirPlay 2 默认 true）。 */
    val requiresEncryptedPairing: Boolean = true
)

/**
 * 设备类型（用于 UI 展示与功能裁剪）。
 */
enum class DeviceType { APPLE_TV, HOMEPOD, SONOS, MAC, XIAOMI, DLNA, UNKNOWN }

/**
 * 一台被发现的 AirPlay 接收设备。
 */
data class AirDevice(
    /** mDNS 服务名 / 设备友好名。 */
    val name: String,
    val host: String,
    val port: Int,
    val type: DeviceType = DeviceType.UNKNOWN,
    val capabilities: DeviceCapabilities = DeviceCapabilities(),
    /** 原始 TXT 记录，便于调试与能力解析。 */
    val txtRecords: Map<String, String> = emptyMap(),
    /** DLNA 渲染器的 AVTransport 绝对 controlURL；非 DLNA 设备为 null。 */
    val controlUrl: String? = null,
) {
    val id: String get() = "$host:$port"
}
