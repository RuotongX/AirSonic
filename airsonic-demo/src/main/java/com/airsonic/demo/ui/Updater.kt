// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.airsonic.demo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 在线更新。
 *
 * 流程：查 `releases/latest` → 比对 [BuildConfig.VERSION_NAME] → 下载 APK → 拉起系统安装器。
 * 仅用 [HttpURLConnection] + 内置 org.json，无额外依赖。公开仓库无需 token。
 *
 * 端到端（见 .github/workflows/release.yml）：推送 `v*` tag → CI 用固定密钥构建签名 APK
 * 并发布 Release → app 在此查到新版即可覆盖安装（签名一致）。
 */
object Updater {
    /** 改成你的 GitHub 仓库。 */
    const val OWNER = "chunguangwei"
    const val REPO = "AirSonic"

    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 一次发布。[versionName] 已去掉前缀 v。 */
    data class Release(
        val versionName: String,
        val notes: String,
        val apkUrl: String?,
        val htmlUrl: String,
    )

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    // ---- 更新 UI 状态（单例：离开设置页再回来不重来） ----
    val uiStatus = androidx.compose.runtime.mutableStateOf("idle")   // idle/checking/uptodate/available/downloading/failed
    val uiRelease = androidx.compose.runtime.mutableStateOf<Release?>(null)
    val uiProgress = androidx.compose.runtime.mutableStateOf(0)
    val uiDownloadedApk = androidx.compose.runtime.mutableStateOf<File?>(null)

    /** 查最新发布；网络/解析失败返回 null。 */
    suspend fun checkLatest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val json = httpGetString(API) ?: return@runCatching null
            val obj = JSONObject(json)
            val tag = obj.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (tag.isEmpty()) return@runCatching null
            var apkUrl: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url"); break
                    }
                }
            }
            Release(
                versionName = tag,
                notes = obj.optString("body").ifBlank { "" },
                apkUrl = apkUrl,
                htmlUrl = obj.optString("html_url"),
            )
        }.getOrNull()
    }

    /** remote 是否比 current 新（语义化版本逐段数字比较）。 */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parseVer(remote); val c = parseVer(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }; val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parseVer(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_', '+')
            .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }

    // ---- 系统 DownloadManager 下载（切后台/锁屏不断，下完回来可直接装） ----

    private const val PREF = "airsonic_updater"
    private const val KEY_DL_ID = "download_id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun apkFile(ctx: Context): File =
        File(ctx.getExternalFilesDir("updates") ?: ctx.filesDir, "airsonic-update.apk")

    /** 经系统 DownloadManager 后台下载 APK，返回下载 id（持久化，供跨进入查询）。 */
    fun enqueueApkDownload(ctx: Context, url: String): Long {
        val out = apkFile(ctx)
        if (out.exists()) out.delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("AirSonic update")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationUri(Uri.fromFile(out))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        prefs(ctx).edit().putLong(KEY_DL_ID, id).apply()
        return id
    }

    /**
     * 查询上次入队的下载。无记录返回 null；
     * 否则 Triple(status, progress 0..100(未知 -1), 成功时的 APK 文件)，status ∈ running/success/failed。
     */
    fun downloadStatus(ctx: Context): Triple<String, Int, File?>? {
        val id = prefs(ctx).getLong(KEY_DL_ID, -1L)
        if (id < 0) return null
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val pct = if (total > 0) (done * 100 / total).toInt() else -1
            return when (st) {
                DownloadManager.STATUS_SUCCESSFUL ->
                    Triple("success", 100, apkFile(ctx).takeIf { it.exists() && it.length() > 0 })
                DownloadManager.STATUS_FAILED -> Triple("failed", pct, null)
                else -> Triple("running", pct, null)
            }
        }
        return null
    }

    /** 安装已下载的 APK 并清掉下载记录。 */
    fun installDownloadedApk(ctx: Context, apk: File) {
        prefs(ctx).edit().remove(KEY_DL_ID).apply()
        installApk(ctx, apk)
    }

    /** 拉起系统安装器安装下载好的 APK。 */
    fun installApk(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** 打开 Release 网页（fallback / 无 APK 资产时）。 */
    fun openReleasePage(ctx: Context, url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun httpGetString(url: String): String? {
        val conn = openConn(url)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connect()
        if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }

    private fun openConn(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AirSonic-Updater")
        }
}