package io.github.daisukikaffuchino.han1meviewer.logic.hls

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * HLS（`.m3u8`）清单解析。
 *
 * nJAV 系站点的「视频地址」其实是一份 HLS 主清单（master playlist），
 * 里面列着若干码率变体，每个变体又指向一份分片清单（media playlist）。
 * 所以**直接对 `.m3u8` 做字节下载是拿不到视频的** —— 那只会下到一个几 KB 的文本。
 * 想让「下载」真正能用，必须按分片下载再把它们拼起来，
 * 见 [io.github.daisukikaffuchino.han1meviewer.worker.HanimeDownloadWorker.downloadHls]。
 *
 * 这里只做**纯解析**（不联网），方便单测，也方便出问题时对着真实清单核。
 *
 * 实测样例（nJAV / surrit.com，2026-09）：
 *
 * ```
 * #EXTM3U                                     ← master
 * #EXT-X-STREAM-INF:BANDWIDTH=…,RESOLUTION=1280x720
 * 720p/video.m3u8                             ← 相对路径，必须按清单地址解析
 * ```
 *
 * ```
 * #EXTM3U                                     ← media
 * #EXT-X-TARGETDURATION:4
 * #EXT-X-PLAYLIST-TYPE:VOD
 * #EXT-X-TOKEN=…                              ← 自定义标签，直接忽略
 * #EXTINF:4.000,
 * video0.jpeg                                 ← 其实是 MPEG-TS，服务端把 Content-Type 谎报成 image/jpeg
 * ```
 */
object HlsPlaylist {

    /** 判断一个下载地址是不是 HLS 清单（忽略 query / fragment）。 */
    fun isPlaylistUrl(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?')
        return path.endsWith(".m3u8", ignoreCase = true)
    }

    /** 主清单（含变体）与分片清单的区别就在这一行标签。 */
    fun isMaster(body: String): Boolean = body.contains("#EXT-X-STREAM-INF")

    /** 主清单里的一个码率变体。 */
    data class Variant(
        val url: String,
        /** `RESOLUTION=1280x720` 里的高；站点没写就是 null。 */
        val height: Int?,
        /** `BANDWIDTH=…`，用来在认不出清晰度时挑最高档。 */
        val bandwidth: Int,
    )

    /** 分片清单里的一个分片。 */
    data class Segment(
        val url: String,
        /** `#EXTINF` 里的秒数，只用于统计总时长。 */
        val durationSeconds: Double,
    )

    /** 一份分片清单。 */
    data class Media(
        /** `#EXT-X-MAP` 指定的初始化分片（有些流用它装 moov），没有就是 null。 */
        val initSegment: String?,
        val segments: List<Segment>,
    ) {
        val totalDurationSeconds: Double get() = segments.sumOf { it.durationSeconds }
    }

    fun parseMaster(baseUrl: String, body: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        var pendingHeight: Int? = null
        var pendingBandwidth = 0
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = line.substringAfter(':')
                    pendingHeight = RESOLUTION_ATTR.find(attrs)
                        ?.groupValues?.getOrNull(2)?.toIntOrNull()
                    pendingBandwidth = BANDWIDTH_ATTR.find(attrs)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                }
                // 变体的地址紧跟在 #EXT-X-STREAM-INF 后面
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    variants += Variant(resolve(baseUrl, line), pendingHeight, pendingBandwidth)
                    pendingHeight = null
                    pendingBandwidth = 0
                }
            }
        }
        return variants
    }

    fun parseMedia(baseUrl: String, body: String): Media {
        var initSegment: String? = null
        var pendingDuration = 0.0
        val segments = mutableListOf<Segment>()
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = URI_ATTR.find(line)?.groupValues?.getOrNull(1)
                    if (!uri.isNullOrBlank()) initSegment = resolve(baseUrl, uri)
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(':')
                        .substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    segments += Segment(resolve(baseUrl, line), pendingDuration)
                    pendingDuration = 0.0
                }
            }
        }
        return Media(initSegment, segments)
    }

    /**
     * 相对地址 → 绝对地址。
     *
     * ⚠️ 必须拿**清单自己的地址**当基准（而不是站点根），因为分片是
     * `720p/video0.jpeg` 这种相对写法。用 `HttpUrl.resolve` 而不是手工拼字符串：
     * 它能正确处理 `../`、根路径 `/xxx` 与 `?query` 这几种写法。
     */
    private fun resolve(baseUrl: String, uri: String): String {
        if (uri.startsWith("http://", ignoreCase = true) ||
            uri.startsWith("https://", ignoreCase = true)
        ) {
            return uri
        }
        return baseUrl.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri
    }

    private val RESOLUTION_ATTR = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val BANDWIDTH_ATTR = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
    private val URI_ATTR = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
}
