package com.airsonic.sender.streaming

/** 解析 HTTP Range 头，返回闭区间 [start,end]（含），无头则全量，非法返回 null。 */
object HttpRange {
    fun parse(header: String?, totalLen: Long): Pair<Long, Long>? {
        if (totalLen <= 0) return null
        if (header.isNullOrBlank()) return 0L to (totalLen - 1)
        val spec = header.trim().removePrefix("bytes=").trim()
        if (!spec.contains('-')) return null
        val (a, b) = spec.split('-', limit = 2)
        return try {
            when {
                a.isEmpty() && b.isEmpty() -> null
                a.isEmpty() -> {
                    val n = b.toLong().coerceIn(0, totalLen)
                    (totalLen - n).coerceAtLeast(0) to (totalLen - 1)
                }
                b.isEmpty() -> {
                    val s = a.toLong()
                    if (s >= totalLen) null else s to (totalLen - 1)
                }
                else -> {
                    val s = a.toLong(); val e = b.toLong().coerceAtMost(totalLen - 1)
                    if (s < 0 || s > e) null else s to e
                }
            }
        } catch (_: NumberFormatException) { null }
    }
}
