# AirSonic

> An Android app that casts audio (and works toward video) to AirPlay receivers — HomePod, Apple TV, AirPlay speakers — implemented from scratch, with no commercial SDK.

AirSonic 是一个纯自研的 Android 投送 app：自己实现 AirPlay 设备发现、HomeKit/AirPlay2 配对、加密音频流，配上一套「深空极光 / Aurora」风格的 Jetpack Compose 界面。

## ✨ 功能

- **设备发现**：mDNS 实时发现局域网内的 AirPlay 接收器。
- **音频投送**：向 HomePod / AirPlay 音箱投送本地音频（ALAC / PCM 自动选择）。
- **AirPlay2 配对**：完整 HomeKit M1–M6 + pair-verify（X25519 / HKDF / ChaCha20-Poly1305）。
- **设备管理**：每台设备可重命名、永久隐藏 / 取消隐藏（本地持久化）。
- **本地媒体浏览器**：app 内浏览本机音视频并投送。
- **中英文界面**：设置页一键切换。
- **应用内更新**：设置页「检查更新」直接从 GitHub Releases 拉取新版并安装（见下）。

## 🧱 工程结构

```
AirSonic/
├── airsonic-sender/   # 核心库：设备发现 / 配对 / 加密 / 流式
│   └── src/main/java/com/airsonic/sender/
│       ├── api/         # 对外模型与接口（AirDevice / AirSonicClient）
│       ├── discovery/   # mDNS 发现（NsdManager + MulticastLock）
│       ├── pairing/     # AirPlay2 配对：TLV8 / X25519 / HKDF / ChaCha20 / 握手
│       └── streaming/   # RTP 音频、加密通道、视频会话控制
└── airsonic-demo/     # 产品 app（Compose / Aurora UI）
    └── src/main/java/com/airsonic/demo/ui/
        ├── StudioActivity   # 入口
        ├── Screens.kt       # 主页 / 媒体 / 设置等界面
        ├── CastEngine.kt    # 投送编排
        ├── Updater.kt       # GitHub Releases 在线更新
        └── DevicePrefs.kt   # 每设备持久化设置
```

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

## ⚠️ 说明

本项目为学习 / 研究 AirPlay 协议而自研实现，不依赖任何商业 SDK，也不含任何 Apple 私有代码。仅供个人学习与互操作研究使用。
