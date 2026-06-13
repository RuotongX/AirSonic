# AirSonic

> A from-scratch Android casting app — sends audio & video to **AirPlay** (HomePod, Apple TV, AirPlay speakers) **and DLNA/UPnP** receivers (smart TVs, boxes, Kodi). No commercial SDK.

AirSonic 是一个纯自研的 Android 投送 app：自己实现两条投送链路 —— **AirPlay**（设备发现 / HomeKit-AirPlay2 配对 / 加密音频流）与 **DLNA/UPnP**（SSDP 发现 / SOAP 控制），配上一套「深空极光 / Aurora」风格的 Jetpack Compose 界面。

## ✨ 功能

- **双协议投送**：AirPlay 与 DLNA/UPnP 设备出现在**同一个设备列表**里，按图标/标签区分，用户无感。
- **AirPlay 音频**：向 HomePod / AirPlay 音箱投送本地音频（ALAC / PCM 自动选择）；完整 HomeKit M1–M6 + pair-verify（X25519 / HKDF / ChaCha20-Poly1305）。
- **系统音频镜像**：捕获手机系统声音（任意 app / 网页）实时投到所选音箱 —— AirPlay 走 RAOP、**Sonos 走 UPnP 实时音频流**（「手机镜像」→「投声音」）。
- **投画面（整屏镜像）**：App 自身只做音频，**画面镜像交由系统无线投屏（Miracast）** —— 「手机镜像」页一键调起系统投屏，投到电视/投影。
- **DLNA 视频 + 音频**：向智能电视 / 盒子 / Kodi 等 MediaRenderer 投送本地视频/音频，支持播放/暂停/拖动/停止 + 进度（AVTransport SOAP）。
- **实时频谱**：投送页跟着音乐跳动的 FFT 频谱条（纯 Kotlin Cooley-Tukey，无依赖）。
- **设备发现**：mDNS（AirPlay `_airplay._tcp`）+ SSDP（DLNA `MediaRenderer`）实时发现局域网设备。
- **设备管理**：每台设备可重命名、永久隐藏 / 取消隐藏（本地持久化）。
- **本地媒体浏览器**：app 内浏览本机音视频并投送。
- **中英文界面**：设置页一键切换。
- **应用内更新**：设置页「检查更新」直接从 GitHub Releases 拉取新版并安装（见下）。

> **安全**：DLNA 设备描述 / controlUrl / LOCATION 均来自未信任的局域网设备，已做统一防护 —— 仅 http(s)+RFC1918 内网地址、解析一次钉死 IP（防 SSRF / DNS rebinding）、限读 512KB（防 DoS）、解析禁 DOCTYPE（防 XXE）。

## 🧱 工程结构

```
AirSonic/
├── airsonic-sender/   # 核心库：设备发现 / 配对 / 加密 / 流式
│   └── src/main/java/com/airsonic/sender/
│       ├── api/         # 对外模型与接口（AirDevice / AirSonicClient）
│       ├── discovery/   # AirplayDiscovery（mDNS）+ DlnaDiscovery（SSDP）+ SsdpParsing
│       ├── pairing/     # AirPlay2 配对：TLV8 / X25519 / HKDF / ChaCha20 / 握手
│       ├── dlna/        # DLNA：DlnaProtocol（SOAP/DIDL 纯函数）/ DlnaController / LanHttp（安全访问）
│       └── streaming/   # RTP 音频、加密通道、视频会话控制、系统音频实时流、FFT 频谱、LocalMediaHttpServer
└── airsonic-demo/     # 产品 app（Compose / Aurora UI）
    └── src/main/java/com/airsonic/demo/ui/
        ├── StudioActivity   # 入口
        ├── Screens.kt       # 主页 / 媒体 / 设置等界面
        ├── CastEngine.kt    # 投送编排（合并 AirPlay + DLNA 发现与投送）
        ├── Updater.kt       # GitHub Releases 在线更新
        └── DevicePrefs.kt   # 每设备持久化设置
```

DLNA 的协议逻辑（SOAP 信封 / DIDL-Lite / SSDP 与设备描述解析 / 时间互转 / URL 安全校验）都拆成**无 Android 依赖的纯函数**，桌面 JVM 全覆盖单测；网络类（`DlnaController` / `DlnaDiscovery`）只是薄壳。

## 🔄 应用内更新（GitHub Releases 驱动）

app 的「检查更新」直接读取本仓库的 **latest release**，比对版本号，新版即下载 APK 并拉起系统安装器。

发布新版本只需推送一个 `v*` tag——GitHub Actions 会自动构建**签名** APK 并发布 Release：

```bash
# 1. 提升版本号
#    airsonic-demo/build.gradle.kts: versionCode += 1, versionName = "0.2.0"
# 2. 打 tag 推送
git tag v0.2.0
git push origin v0.2.0
# → .github/workflows/release.yml 构建签名 APK → 发布 Release
# → 用户在 app 设置页点「检查更新」即可升级
```

签名密钥由 GitHub Secrets 注入（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
**不放进仓库**。同一把密钥保证每个版本签名一致，所以 app 内更新能直接覆盖安装。

> 首次安装请从 [Releases](../../releases) 页下载 APK；之后的升级都可在 app 内完成。

## 🛠 构建

依赖本机 Android SDK（配置在 `local.properties` 的 `sdk.dir`）。

```bash
./gradlew :airsonic-demo:assembleDebug      # Debug APK
./gradlew :airsonic-demo:assembleRelease    # Release APK（本地无密钥时回退 debug 签名）
```

APK 输出：`airsonic-demo/build/outputs/apk/`。

## 🧩 环境

| 项 | 版本 |
|----|------|
| compileSdk | 33 |
| minSdk | 29 |
| Kotlin | 1.8.22 |
| AGP | 7.4.2 |
| Compose | BOM 2023.06.01 |
| 加密库 | BouncyCastle 1.76 |

## 📺 支持的接收设备

| 协议 | 设备 | 内容 |
|------|------|------|
| AirPlay / AirPlay 2 | HomePod、AirPlay 音箱、Apple TV、Mac | 音频（本地文件 + 系统音频镜像；视频投 Apple TV 仍在攻坚） |
| UPnP 实时流 | Sonos | 系统音频镜像（`x-rincon-mp3radio` 实时流） |
| DLNA / UPnP | 智能电视、电视盒子、Kodi、PC 软渲染器 | 本地视频 + 音频 |
| 系统无线投屏（Miracast） | 电视 / 投影（如坚果）等 Miracast 接收端 | 整屏画面镜像（由系统驱动，App 仅一键调起） |

## 📄 授权 / License

本项目采用 **[PolyForm Noncommercial License 1.0.0](LICENSE)**（非商业许可证）：

- ✅ **个人 / 学习 / 研究 / 业余 / 非营利 / 教育 / 政府**等任何**非商业**用途——**免费授权**。
- ⛔ **任何商业用途，须事先获得作者书面同意**（单独的商业许可）。

> **商用请先联系作者获取授权：chunguangwee@gmail.com**
> Commercial use requires the author's prior written consent — contact **chunguangwee@gmail.com**.

完整条款见 [`LICENSE`](LICENSE)。

### 用户协议与隐私政策

- 📜 [用户协议 / Terms of Use](TERMS.md)
- 🔒 [隐私政策 / Privacy Policy](PRIVACY.md)

**隐私要点：AirSonic 没有服务器，不收集、不上传任何个人数据；所有媒体内容与控制指令只在你的局域网内传输。** 唯一的对外请求是可选的"检查更新"（读取 GitHub 公开 Release 信息）。应用设置页内也可直接查看以上两份文件。

## ⚠️ 说明

本项目为学习 / 研究 AirPlay 与 DLNA/UPnP 协议而自研实现，不依赖任何商业 SDK，也不含任何 Apple 私有代码（DLNA 为开放标准）。
