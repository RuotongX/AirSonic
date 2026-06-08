package com.airsonic.demo.ui

import android.content.Context
import android.content.Intent
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

    /**
     * 下载 APK 到 externalCacheDir。[onProgress] 回调 0..100（总长未知时回调 -1）。
     * 返回本地文件；失败返回 null。
     */
    suspend fun downloadApk(ctx: Context, url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = ctx.externalCacheDir ?: ctx.cacheDir
                val out = File(dir, "airsonic-update.apk")
                if (out.exists()) out.delete()
                val conn = openConn(url)
                conn.connect()
                if (conn.responseCode !in 200..299) { conn.disconnect(); return@runCatching null }
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int; var done = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            output.write(buf, 0, read)
                            done += read
                            onProgress(if (total > 0) ((done * 100) / total).toInt() else -1)
                        }
                    }
                }
                conn.disconnect()
                out
            }.getOrNull()
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
