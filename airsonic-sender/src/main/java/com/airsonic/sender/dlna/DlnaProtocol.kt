package com.airsonic.sender.dlna

/** DLNA/UPnP 纯协议逻辑（无 Android 依赖，便于 JVM 单测）。 */

/** DLNA 内容特征串：OP=01 支持 byte-range/seek；与 LocalMediaHttpServer 的 contentFeatures 头一致。 */
const val DLNA_CONTENT_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"

/** XML 文本转义（5 个实体）。 */
fun escapeXml(s: String): String = buildString(s.length + 16) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}

/** 秒 → H:MM:SS（UPnP REL_TIME）。 */
fun secToHms(sec: Double): String {
    val total = sec.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "%d:%02d:%02d".format(h, m, s)
}

/** H:MM:SS → 秒；空/NOT_IMPLEMENTED/非法 → 0.0。 */
fun hmsToSec(hms: String): Double {
    val t = hms.trim()
    if (t.isEmpty() || t.equals("NOT_IMPLEMENTED", true)) return 0.0
    val parts = t.split(":")
    return runCatching {
        when (parts.size) {
            3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()
            2 -> parts[0].toLong() * 60 + parts[1].toDouble()
            1 -> parts[0].toDouble()
            else -> 0.0
        }
    }.getOrDefault(0.0)
}
