package com.airsonic.sender.streaming

import java.io.InputStream

/** 被投送媒体的数据源（与 Android 解耦：实现方负责 content:// / 文件打开）。 */
interface RangeSource {
    /** 总字节数；未知返回 -1（V1 要求已知，便于 Range）。 */
    val length: Long
    /** MIME，如 "video/mp4"。 */
    val mimeType: String
    /** 从 offset 字节处打开一个新的可读流（调用方负责关闭）。 */
    fun open(offset: Long): InputStream
}
