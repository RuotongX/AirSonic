# 隐私政策 / Privacy Policy

**生效日期：2026-06-10　·　适用：AirSonic（Android）**

> **本版本说明**：本应用为 **RuotongX 魔改版**（fork 自 [chunguangwei/AirSonic](https://github.com/chunguangwei/AirSonic)，魔改者：Ruotong Xu）。上游版权归 Chunguang Wei 所有，魔改部分版权归 Ruotong Xu 所有；本 fork 与上游同样适用 **PolyForm Noncommercial License 1.0.0**，仅限非商业用途。魔改不影响下述隐私承诺——本版本同样**没有服务器、不收集、不上传任何个人数据**。

> English summary follows the Chinese text below.

AirSonic 是一款在本地局域网内把音频 / 视频投送到接收设备（AirPlay / DLNA / Sonos 等）的工具类应用。我们把隐私保护作为首要原则：**AirSonic 没有服务器，不收集、不上传、不出售你的任何个人数据。**

## 1. 我们不收集什么

- ❌ 不收集账号、手机号、邮箱、位置、通讯录、设备唯一标识用于追踪。
- ❌ 没有任何第三方统计 / 广告 / 埋点 SDK。
- ❌ 不把你的媒体文件、音频内容、设备列表上传到任何服务器（我们也没有服务器）。

## 2. App 申请的权限及用途

| 权限 | 用途 | 数据去向 |
|---|---|---|
| 录音 / `RECORD_AUDIO` + 媒体投影 / `MEDIA_PROJECTION` | 捕获**系统音频**以实时投送（如把手机里播放的音乐投到音箱） | 仅经局域网实时流发往**你选择的接收设备**，不落盘、不上传 |
| 读取媒体音视频 / `READ_MEDIA_AUDIO`·`READ_MEDIA_VIDEO`·`READ_EXTERNAL_STORAGE` | 浏览并投送你**本机的音视频文件** | 文件经本机临时 HTTP 服务在局域网内直接发往接收设备，不上传 |
| 网络 / WLAN 状态 / 组播 / `INTERNET`·`ACCESS_WIFI_STATE`·`CHANGE_WIFI_MULTICAST_STATE` 等 | 通过 mDNS / SSDP 在局域网内**发现接收设备**并建立投送连接 | 仅与同一局域网内的设备通信 |
| 前台服务 / `FOREGROUND_SERVICE`(`mediaProjection`) | 投送期间保持会话存活、并在通知栏明示"正在捕获/投送" | 本机 |
| 安装应用 / `REQUEST_INSTALL_PACKAGES` | "检查更新"下载新版 APK 后触发系统安装 | 见第 3 条 |

**核心事实：所有媒体内容和控制指令都只在你的局域网内、于本机与你主动选择的接收设备之间传输，不经过任何中间服务器。**

## 3. 唯一的对外网络请求：检查更新

App 设置页的"检查更新"会向 **GitHub Releases 公共 API**（`api.github.com` / `github.com`）发起请求，以获取最新版本号并（经你确认后）下载 APK。此过程：

- 只读取 GitHub 公开的 Release 信息，不发送你的任何个人数据；
- 适用 GitHub 自身的隐私政策（数据由 GitHub 处理，我们不接触）；
- 你可以完全不使用该功能。

## 4. 数据存储

App 仅在本机本地保存少量**偏好设置**（如语言、"强制 ALAC""Sonos 改投 WAV"开关、设备重命名 / 隐藏、AirPlay 配对凭据）。这些数据：

- 全部存于应用私有目录（`SharedPreferences`），不离开你的设备；
- 卸载 App 即清除；
- 配对凭据仅用于免重复输入 PIN 地连接已配对的接收设备。

## 5. 儿童

本 App 不面向 13 岁以下儿童设计，也不会有意收集其信息（事实上不收集任何人的信息）。

## 6. 变更

隐私政策如有更新，将更新本文件的"生效日期"并通过新版本发布。

## 7. 联系与反馈

隐私问题、Bug 反馈与建议，请联系：**chunguangwee@gmail.com**
（应用内「设置 › 用户协议与隐私 › 联系与反馈」可直接发邮件。）

---

## English Summary

AirSonic casts audio/video to receivers (AirPlay / DLNA / Sonos) **within your local network only**. It has **no server and collects, uploads, or sells no personal data**. There are **no analytics/ads/tracking SDKs**.

- Captured system audio and your local media files are streamed **only to the receiver you pick, over your LAN** — never uploaded.
- Network/Wi-Fi permissions are used solely to **discover receivers via mDNS/SSDP** on the same LAN.
- The **only outbound request** is the optional "Check for updates", which reads public **GitHub Releases** info (governed by GitHub's own privacy policy) to fetch the latest APK; no personal data is sent.
- A few local **preferences and AirPlay pairing credentials** are stored in the app's private storage and removed on uninstall.

Contact / bug reports: **chunguangwee@gmail.com**
