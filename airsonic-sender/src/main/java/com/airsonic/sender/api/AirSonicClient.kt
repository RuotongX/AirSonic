// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.api

/**
 * 设备发现回调。
 */
interface DeviceListener {
    fun onDeviceFound(device: AirDevice)
    fun onDeviceLost(device: AirDevice)
    fun onDiscoveryFailed(reason: String)
}

/**
 * AirSonic 对外 SDK 接口（草案，随 Phase 推进逐步实现）。
 */
interface AirSonicClient {
    fun startDiscovery(listener: DeviceListener)
    fun stopDiscovery()
}