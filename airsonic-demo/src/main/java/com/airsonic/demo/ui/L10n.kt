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
    val resolution: String,
    val frameRate: String,
    val comingSoon: String,
    val sound: String,
    val soundOnly: String,
    val soundOnlyDesc: String,
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
)

private val ZH = Strings(
    tagline = "把声音，投向空间",
    skip = "跳过", selectDevice = "选择设备", settings = "设置",
    localMedia = "投射本地媒体", video = "视频", audio = "音频",
    mirrorTitle = "手机镜像", mirrorSub = "把手机声音实时投到设备",
    resolution = "分辨率", frameRate = "帧率", comingSoon = "即将推出",
    sound = "声音", soundOnly = "仅投声音",
    soundOnlyDesc = "捕获系统音频实时投送到所选设备（视频画面即将推出）",
    startMirror = "开始镜像",
    castVideo = "投射视频", castAudio = "投射音频",
    needMedia = "需要访问本机媒体", grantDesc = "授权后即可浏览并投送文件", grant = "授权访问",
    noVideo = "未找到视频文件", noAudio = "未找到音频文件",
    devicesOnWifi = "当前 WiFi 下的设备", refresh = "刷新", noDevices = "未发现设备", notSupported = "暂不支持",
    supportTitle = "支持设备", supportSub = "支持 AirPlay 协议的设备",
    howTitle = "投送说明", howSub = "投送时手机媒体音量自动压低，仅 AirPlay 设备出声",
    debugTitle = "调试 · 验证点", debugSub = "开发者调试入口",
    version = "AirSonic · 版本 0.1.0", language = "语言",
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
)

private val EN = Strings(
    tagline = "Cast your sound into the room",
    skip = "Skip", selectDevice = "Select device", settings = "Settings",
    localMedia = "Local media", video = "Video", audio = "Audio",
    mirrorTitle = "Phone Mirror", mirrorSub = "Stream your phone's sound to the device",
    resolution = "Resolution", frameRate = "Frame rate", comingSoon = "Coming soon",
    sound = "Sound", soundOnly = "Sound only",
    soundOnlyDesc = "Capture system audio and stream it live (video mirroring coming soon)",
    startMirror = "Start mirroring",
    castVideo = "Cast video", castAudio = "Cast audio",
    needMedia = "Media access needed", grantDesc = "Grant access to browse and cast files", grant = "Grant access",
    noVideo = "No video files", noAudio = "No audio files",
    devicesOnWifi = "Devices on this Wi-Fi", refresh = "Refresh", noDevices = "No devices found", notSupported = "Not supported",
    supportTitle = "Supported devices", supportSub = "Any AirPlay-compatible device",
    howTitle = "How it works", howSub = "Phone media volume is lowered while casting; sound plays on the AirPlay device only",
    debugTitle = "Debug · Test points", debugSub = "Developer entry",
    version = "AirSonic · Version 0.1.0", language = "Language",
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
