package io.github.daisukikaffuchino.han1meviewer.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一部影片的播放偏好。字段可空：用户可能只调了倍速、没动画质。 */
@Serializable
data class PlaybackPref(
    val speed: Float? = null,
    val quality: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * 按影片记住倍速与画质。
 *
 * ## 为什么值得单独记
 *
 * 原先倍速/画质只有**全局**一份：在 A 片里调到 1.5x，回到 B 片还是 1.5x；反过来，
 * 想给某部特别慢的番固定 1.5x，每次打开都得重调一遍。这对「连续追同一部」的场景
 * 尤其烦 —— 一集看完退出，下一集又变回 1.0x。
 *
 * 这里把偏好跟着 `videoCode` 存下来，下次打开同一部自动恢复。没记过的仍走全局默认值，
 * 所以对新片的行为与以前完全一致。
 *
 * ## 用设置而不是新表
 *
 * 数据量是「用户实际看过的片数」，几百条封顶（[MAX_ENTRIES]），而且是纯 KV 语义，
 * 为它在 Room 里开一张表、再写一次迁移，收益不匹配。存进设置里还可以直接被现有的
 * 备份/导出流程带上，不用额外改动。
 */
object PlaybackMemory {

    /** 上限。超了按 [PlaybackPref.updatedAt] 淘汰最旧的 —— 记 300 部够用了。 */
    private const val MAX_ENTRIES = 300

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 当前是否启用。关掉时所有读取返回 `null`、所有写入直接丢弃。 */
    val enabled: Boolean get() = SettingsRepository.rememberPerVideoPlayback

    /** 记住了多少部。 */
    val size: Int get() = read().size

    fun speedFor(videoCode: String): Float? =
        if (!enabled) null else read()[videoCode]?.speed

    fun qualityFor(videoCode: String): String? =
        if (!enabled) null else read()[videoCode]?.quality?.takeIf { it.isNotBlank() }

    suspend fun rememberSpeed(videoCode: String, speed: Float) =
        update(videoCode) { it.copy(speed = speed) }

    suspend fun rememberQuality(videoCode: String, quality: String) =
        update(videoCode) { it.copy(quality = quality) }

    suspend fun clear() {
        SettingsRepository.update { it.copy(perVideoPlaybackJson = "") }
    }

    private fun read(): Map<String, PlaybackPref> =
        runCatching {
            json.decodeFromString<Map<String, PlaybackPref>>(
                SettingsRepository.perVideoPlaybackJson
            )
        }.getOrDefault(emptyMap())

    private suspend fun update(videoCode: String, transform: (PlaybackPref) -> PlaybackPref) {
        if (videoCode.isBlank() || !enabled) return
        val current = read()
        val next: Map<String, PlaybackPref> = current + (
                videoCode to transform(current[videoCode] ?: PlaybackPref())
                    .copy(updatedAt = System.currentTimeMillis())
                )
        val trimmed: Map<String, PlaybackPref> = if (next.size <= MAX_ENTRIES) {
            next
        } else {
            next.entries
                .sortedByDescending { it.value.updatedAt }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }
        SettingsRepository.update { it.copy(perVideoPlaybackJson = json.encodeToString(trimmed)) }
    }
}
