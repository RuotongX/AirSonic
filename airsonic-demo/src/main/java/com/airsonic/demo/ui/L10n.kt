// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** 语言。 */
enum class Lang { ZH, EN }

/** 全部界面文案（中/英）。 */
data class Strings(
    val tagline: String,
    val skip: String,              // 跳过 / Skip  (后接 "5s")
    val selectDevice: String,
    val settings: String,
    val localMedia: String,
    val video: String,
    val audio: String,
    val mirrorTitle: String,       // 手机镜像 / Phone Mirror
    val mirrorSub: String,
    val mirrorPicture: String,     // 投画面（整屏，应用内投屏 DLNA）/ Mirror screen
    val resolution: String,
    val frameRate: String,
    val comingSoon: String,
    val sound: String,
    val soundOnly: String,
    val soundOnlyDesc: String,
    val soundInfo: String,           // 仅投声音 ⓘ 弹窗说明
    val startMirror: String,
    val castVideo: String,
    val castAudio: String,
    val needMedia: String,
    val grantDesc: String,
    val grant: String,
    val noVideo: String,
    val noAudio: String,
    val devicesOnWifi: String,
    val refresh: String,
    val noDevices: String,
    val notSupported: String,
    val supportTitle: String,
    val supportSub: String,
    val howTitle: String,
    val howSub: String,
    val debugTitle: String,
    val debugSub: String,
    val version: String,
    val licenseLine: String,       // 设置页底部版权/许可声明
    val language: String,
    val back: String,
    val device: String,
    val castingTo: String,         // 正在投送 · / Casting ·
    val stop: String,
    val connecting: String,        // 正在连接 / Connecting to
    val stopped: String,
    val playFinished: String,
    val castError: String,         // 投送异常： / Cast error:
    val pairFail: String,
    val setupFail: String,
    val captureFail: String,
    val openFail: String,
    val noDevice: String,
    val discoverFail: String,      // 发现失败： / Discovery failed:
    val notSupportedSuffix: String, // 暂不支持 / not supported（用于 "X 暂不支持"）
    val videoOnTv: String,
    val pause: String,
    val resume: String,
    val pinTitle: String,
    val pinHint: String,
    val confirm: String,
    val cancel: String,
    val rename: String,
    val hide: String,
    val unhide: String,
    val hiddenDevices: String,
    val renameHint: String,
    val checkUpdate: String,       // 检查更新 / Check for updates
    val checkUpdateSub: String,    // 当前版本 vX / Current vX
    val checking: String,          // 正在检查… / Checking…
    val upToDate: String,          // 已是最新版本 / Up to date
    val newVersion: String,        // 发现新版本 / New version
    val download: String,          // 下载并安装 / Download & install
    val downloading: String,       // 下载中 / Downloading
    val updateFailed: String,      // 检查失败 / Check failed
    val openInBrowser: String,     // 在浏览器打开 / Open in browser
    val dlnaDevice: String,         // DLNA 设备 / DLNA device
    val protocolsTitle: String,     // 支持的协议 / Supported protocols
    val protocolsSub: String,       // AirPlay · DLNA · 屏幕镜像
    val tagSystem: String,          // 系统 / System
    val protoAirplay: String,       // AirPlay / AirPlay 2
    val protoAirplaySub: String,    // HomePod · 音箱 · Apple TV · Mac
    val protoDlna: String,          // DLNA / UPnP
    val protoDlnaSub: String,       // 智能电视 · 盒子 · Kodi
    val tagAudio: String,           // 音频 / Audio
    val tagVideoAudio: String,      // 视频+音频 / Video+Audio
    val forceAlacTitle: String,     // 强制 ALAC 编码
    val forceAlacSub: String,       // Sonos 等只收 ALAC 的设备…
    val codecLabel: String,         // 编码 / Codec（投送时显示当前编码）
    val sonosWavTitle: String,      // Sonos 改投 WAV
    val sonosWavSub: String,        // AAC 不出声时打开…
    val legalTitle: String,         // 用户协议与隐私
    val legalSub: String,           // 用户协议 · 隐私政策
    val contactHeading: String,     // 联系与反馈
    val contactBody: String,        // 反馈/Bug/商用 邮件入口说明
    val termsHeading: String,       // 用户协议
    val termsBody: String,
    val privacyHeading: String,     // 隐私政策
    val privacyBody: String,
    val viewFullOnGitHub: String,   // 在 GitHub 查看完整条款
    val volumeLabel: String,        // 投送中音量标签
    val mirrorInAppTitle: String,   // 应用内投屏（DLNA 实时屏幕流）
    val mirrorInAppSub: String,     // 录屏实时投到选中设备…延迟说明
    val mirrorNeedsDlna: String,    // 应用内投屏需选中 DLNA 设备
    val tvDisconnected: String,     // 电视端已断开/停止，投送结束
    val volumeUnsupported: String,  // 音量下发失败提示（设备不支持）
    val bgKeepHint: String,         // 后台保活引导（点按跳系统设置）
    val cleanedLastSession: String, // 冷启动清理上次投送残留
    val httpStreamTitle: String,    // HTTP 流输出（AirMusic 式直播地址）
    val httpStreamSub: String,      // 复制地址，任意播放器可收听
    val httpStreamName: String,     // 投送中的设备名占位（无目标设备）
    val copyUrl: String,            // 复制地址
    val copied: String,             // 已复制
)

private val ZH = Strings(
    tagline = "把声音，投向空间",
    skip = "跳过", selectDevice = "选择设备", settings = "设置",
    localMedia = "投射本地媒体", video = "视频", audio = "音频",
    mirrorTitle = "手机镜像", mirrorSub = "画面应用内投屏到电视 · 声音走 AirPlay/Sonos",
    mirrorPicture = "投画面",
    resolution = "分辨率", frameRate = "帧率", comingSoon = "即将推出",
    sound = "声音", soundOnly = "仅投声音",
    soundOnlyDesc = "实时把系统声音投到所选音箱（AirPlay/Sonos）",
    soundInfo = "捕获系统音频，实时投到所选音箱（AirPlay/Sonos）。只投声音、不含画面；要投画面请用上方「投画面」的应用内投屏。",
    startMirror = "开始投声音",
    castVideo = "投射视频", castAudio = "投射音频",
    needMedia = "需要访问本机媒体", grantDesc = "授权后即可浏览并投送文件", grant = "授权访问",
    noVideo = "未找到视频文件", noAudio = "未找到音频文件",
    devicesOnWifi = "当前 WiFi 下的设备", refresh = "刷新", noDevices = "未发现设备", notSupported = "暂不支持",
    supportTitle = "支持设备", supportSub = "支持 AirPlay 协议的设备",
    howTitle = "投送说明", howSub = "投送时手机媒体音量自动压低，仅 AirPlay 设备出声",
    debugTitle = "调试 · 验证点", debugSub = "开发者调试入口",
    version = "AirSonic · 版本",
    licenseLine = "© 2026 Chunguang Wei · PolyForm Noncommercial 1.0.0\n仅限非商业使用，商业使用需作者书面授权\n联系 / Bug 反馈：chunguangwee@gmail.com",
    language = "语言",
    back = "返回", device = "设备",
    castingTo = "正在投送 ·", stop = "停止投送",
    connecting = "正在连接", stopped = "已停止", playFinished = "播放完成",
    castError = "投送异常：", pairFail = "配对失败", setupFail = "SETUP 失败",
    captureFail = "音频捕获初始化失败", openFail = "无法打开文件", noDevice = "未选择设备",
    discoverFail = "发现失败：", notSupportedSuffix = "暂不支持",
    videoOnTv = "正在 TV 上播放", pause = "暂停", resume = "继续",
    pinTitle = "输入配对码", pinHint = "在 TV 屏幕上查看 4 位 PIN", confirm = "确认", cancel = "取消",
    rename = "重命名", hide = "隐藏", unhide = "取消隐藏", hiddenDevices = "隐藏的设备", renameHint = "设备显示名",
    checkUpdate = "检查更新", checkUpdateSub = "当前版本", checking = "正在检查…", upToDate = "已是最新版本",
    newVersion = "发现新版本", download = "下载并安装", downloading = "下载中", updateFailed = "检查失败",
    openInBrowser = "在浏览器打开",
    dlnaDevice = "DLNA 设备",
    protocolsTitle = "支持的协议", protocolsSub = "AirPlay · DLNA · 屏幕镜像", tagSystem = "系统",
    protoAirplay = "AirPlay / AirPlay 2", protoAirplaySub = "HomePod · 音箱 · Apple TV · Mac",
    protoDlna = "DLNA / UPnP", protoDlnaSub = "智能电视 · 盒子 · Kodi",
    tagAudio = "音频", tagVideoAudio = "视频+音频",
    forceAlacTitle = "强制 ALAC 编码",
    forceAlacSub = "一般别开!普通音箱(小米等)强制 ALAC 会无声;只收 ALAC 的设备已自动探测",
    codecLabel = "编码",
    sonosWavTitle = "Sonos 用 WAV 流",
    sonosWavSub = "默认开(无压缩,延迟更低);关闭改投 AAC 电台流(省带宽)",
    legalTitle = "用户协议与隐私",
    legalSub = "用户协议 · 隐私政策",
    contactHeading = "联系与反馈",
    contactBody = "商用授权、Bug 反馈与建议，都欢迎邮件联系作者：",
    termsHeading = "用户协议",
    termsBody = "AirSonic 是自研的投送工具,依 PolyForm 非商业许可授权,商用须作者书面同意。" +
        "请仅用于你拥有或已获授权的设备与媒体,不得用于侵犯他人著作权或隐私;投送、捕获、传播内容的责任由你自负。" +
        "本软件按\"现状\"提供,不对兼容性或投送效果作任何保证,作者不对使用导致的损害担责。" +
        "AirSonic 与 Apple、Sonos 及各电视厂商无任何关联,相关名称仅用于说明兼容性。",
    privacyHeading = "隐私政策",
    privacyBody = "AirSonic 没有服务器,不收集、不上传、不出售任何个人数据,不含任何统计/广告 SDK。" +
        "捕获的系统音频与本机媒体文件只经局域网实时发往你选择的接收设备,不落盘、不上传。" +
        "网络/WLAN 权限仅用于在局域网内发现接收设备。唯一对外请求是可选的\"检查更新\"(读取 GitHub 公开 Release 信息)。" +
        "偏好设置与 AirPlay 配对凭据仅存于本机应用私有目录,卸载即清除。",
    viewFullOnGitHub = "在 GitHub 查看完整条款",
    volumeLabel = "音量",
    mirrorInAppTitle = "应用内投屏",
    mirrorInAppSub = "录屏+系统声音实时投到选中的 DLNA 设备（坚果等），延迟约 1~3 秒",
    mirrorNeedsDlna = "应用内投屏需先在首页选中 DLNA 设备（如坚果投影）",
    tvDisconnected = "电视端已断开，投送结束",
    volumeUnsupported = "音量控制未送达：该设备可能不支持，请用电视遥控器",
    bgKeepHint = "切后台断流？点我跳系统设置允许后台运行（vivo 另需开：自启动 + 后台高耗电）",
    cleanedLastSession = "检测到上次投送未正常结束，已通知电视停止",
    httpStreamTitle = "HTTP 流输出",
    httpStreamSub = "生成直播地址，VLC/浏览器/任意播放器均可收听",
    httpStreamName = "HTTP 流",
    copyUrl = "复制地址",
    copied = "已复制到剪贴板",
)

private val EN = Strings(
    tagline = "Cast your sound into the room",
    skip = "Skip", selectDevice = "Select device", settings = "Settings",
    localMedia = "Local media", video = "Video", audio = "Audio",
    mirrorTitle = "Phone Mirror", mirrorSub = "Screen cast in-app to TV · Sound via AirPlay/Sonos",
    mirrorPicture = "Mirror screen",
    resolution = "Resolution", frameRate = "Frame rate", comingSoon = "Coming soon",
    sound = "Sound", soundOnly = "Sound only",
    soundOnlyDesc = "Live-cast system audio to the chosen speaker (AirPlay/Sonos)",
    soundInfo = "Captures system audio and casts it live to the chosen speaker (AirPlay/Sonos). Sound only, no screen — for screen use the in-app \"Mirror screen\" above.",
    startMirror = "Start sound cast",
    castVideo = "Cast video", castAudio = "Cast audio",
    needMedia = "Media access needed", grantDesc = "Grant access to browse and cast files", grant = "Grant access",
    noVideo = "No video files", noAudio = "No audio files",
    devicesOnWifi = "Devices on this Wi-Fi", refresh = "Refresh", noDevices = "No devices found", notSupported = "Not supported",
    supportTitle = "Supported devices", supportSub = "Any AirPlay-compatible device",
    howTitle = "How it works", howSub = "Phone media volume is lowered while casting; sound plays on the AirPlay device only",
    debugTitle = "Debug · Test points", debugSub = "Developer entry",
    version = "AirSonic · Version",
    licenseLine = "© 2026 Chunguang Wei · PolyForm Noncommercial 1.0.0\nNoncommercial use only; commercial use requires written consent\nContact / bug reports: chunguangwee@gmail.com",
    language = "Language",
    back = "Back", device = "Device",
    castingTo = "Casting ·", stop = "Stop casting",
    connecting = "Connecting to", stopped = "Stopped", playFinished = "Playback finished",
    castError = "Cast error: ", pairFail = "Pairing failed", setupFail = "Setup failed",
    captureFail = "Audio capture failed to start", openFail = "Cannot open file", noDevice = "No device selected",
    discoverFail = "Discovery failed: ", notSupportedSuffix = "not supported",
    videoOnTv = "Playing on TV", pause = "Pause", resume = "Resume",
    pinTitle = "Enter pairing code", pinHint = "See the 4-digit PIN on the TV screen", confirm = "OK", cancel = "Cancel",
    rename = "Rename", hide = "Hide", unhide = "Unhide", hiddenDevices = "Hidden", renameHint = "Display name",
    checkUpdate = "Check for updates", checkUpdateSub = "Current", checking = "Checking…", upToDate = "Up to date",
    newVersion = "New version", download = "Download & install", downloading = "Downloading", updateFailed = "Check failed",
    openInBrowser = "Open in browser",
    dlnaDevice = "DLNA device",
    protocolsTitle = "Supported protocols", protocolsSub = "AirPlay · DLNA · Screen mirroring", tagSystem = "System",
    protoAirplay = "AirPlay / AirPlay 2", protoAirplaySub = "HomePod · Speakers · Apple TV · Mac",
    protoDlna = "DLNA / UPnP", protoDlnaSub = "Smart TV · Box · Kodi",
    tagAudio = "Audio", tagVideoAudio = "Video+Audio",
    forceAlacTitle = "Force ALAC",
    forceAlacSub = "Usually keep OFF! Forcing ALAC silences PCM speakers (e.g. Xiaomi); ALAC-only devices are auto-detected",
    codecLabel = "Codec",
    sonosWavTitle = "Sonos: WAV stream",
    sonosWavSub = "On by default (uncompressed, lower latency); off = AAC radio stream (less bandwidth)",
    legalTitle = "Terms & Privacy",
    legalSub = "Terms of Use · Privacy Policy",
    contactHeading = "Contact & feedback",
    contactBody = "Commercial licensing, bug reports and suggestions — email the author:",
    termsHeading = "Terms of Use",
    termsBody = "AirSonic is a self-built casting tool licensed under PolyForm Noncommercial; commercial use requires the author's written consent. " +
        "Use it only with devices and media you own or are authorized to use; do not use it to infringe others' copyright or privacy — you are solely responsible for what you cast, capture, or share. " +
        "The software is provided \"AS IS\" with no warranty of compatibility or results, and the author is not liable for any damages from its use. " +
        "AirSonic is not affiliated with Apple, Sonos, or any TV vendor; such names are used only to describe compatibility.",
    privacyHeading = "Privacy Policy",
    privacyBody = "AirSonic has no server and collects, uploads, or sells no personal data; it contains no analytics/ads SDK. " +
        "Captured system audio and your local media are streamed only to the receiver you pick, over your LAN — never written to disk or uploaded. " +
        "Network/Wi-Fi permissions are used solely to discover receivers on your LAN. The only outbound request is the optional \"Check for updates\" (reads public GitHub Release info). " +
        "Preferences and AirPlay pairing credentials are stored only in the app's private storage and removed on uninstall.",
    viewFullOnGitHub = "View full text on GitHub",
    volumeLabel = "Volume",
    mirrorInAppTitle = "In-app screen cast",
    mirrorInAppSub = "Mirror screen + system sound live to the selected DLNA device (JMGO etc.), ~1–3s delay",
    mirrorNeedsDlna = "In-app casting needs a DLNA device selected on Home (e.g. JMGO projector)",
    tvDisconnected = "TV disconnected — cast ended",
    volumeUnsupported = "Volume command not accepted by this device — use the TV remote",
    bgKeepHint = "Drops when backgrounded? Tap to allow background run (vivo: also enable auto-start + background high power)",
    cleanedLastSession = "Previous cast did not end cleanly — stop sent to the TV",
    httpStreamTitle = "HTTP stream out",
    httpStreamSub = "Get a live URL — VLC, browsers or any player can tune in",
    httpStreamName = "HTTP stream",
    copyUrl = "Copy URL",
    copied = "Copied to clipboard",
)

object L10n {
    private const val PREF = "airsonic_prefs"
    private const val KEY = "lang"

    /** 当前语言（Compose 状态，切换即刷新 UI）。 */
    val lang = mutableStateOf(Lang.ZH)

    /** 当前字符串表。 */
    val s: Strings get() = if (lang.value == Lang.EN) EN else ZH

    fun load(context: Context) {
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        lang.value = if (v == "en") Lang.EN else Lang.ZH
    }

    fun set(context: Context, l: Lang) {
        lang.value = l
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (l == Lang.EN) "en" else "zh").apply()
    }
}