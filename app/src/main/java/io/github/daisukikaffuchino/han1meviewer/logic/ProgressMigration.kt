package io.github.daisukikaffuchino.han1meviewer.logic

import android.content.Context
import android.net.Uri
import io.github.daisukikaffuchino.han1meviewer.logic.dao.HistoryDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.entity.WatchHistoryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 观看记录的可移植导出 / **合并**导入。
 *
 * ## 为什么不直接用 [BackupManager]
 *
 * [BackupManager] 是「整机快照」：导入时先 `deleteAll()` 再灌回去，等于**覆盖**。
 * 这对「换机后恢复」是对的，但用它做「把另一台设备上看到一半的进度并过来」就很危险 ——
 * 当前的进度会被旧的快照整个抹掉。
 *
 * 所以这里单独做一个**只含观看记录**、且**只合并不覆盖**的通道：
 *
 * - 同一部影片（同 `videoCode`）取**进度更大**的那条；
 * - 观看时间取更新的一条，并把它的标题/封面/releaseDate 带过来（旧记录这些字段可能是空的）；
 * - 应用里已有的、文件里没有的记录，原样保留。
 *
 * ## 时间戳要小心
 *
 * 历史库里的 `watchDate` 混着**秒**与**毫秒**两种量级（早期写秒、后来改毫秒，见
 * [io.github.daisukikaffuchino.han1meviewer.logic.model.WatchStats] 的同一处理）。
 * 直接比较会让「刚写进去的秒级时间戳」看起来比「几年前的毫秒级」还小，
 * 于是合并出「越并越旧」的结果。所以比较前统一归一化成毫秒。
 */
object ProgressMigration {

    private const val FILE_VERSION = 1

    /** 小于这个数的 `watchDate` 视为秒级时间戳。约等于公元 2286 年，不会误判正常的毫秒值。 */
    private const val SECONDS_UPPER_BOUND = 9_999_999_999L

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    private data class ProgressFile(
        val version: Int = FILE_VERSION,
        val exportedAt: Long = System.currentTimeMillis(),
        /** 用 `WatchHistoryEntity` 自身的序列化，旧版本导出的文件也能读。 */
        val items: List<WatchHistoryEntity> = emptyList(),
    )

    /** 合并结果，供 UI 回显「合并了几条、新增了几条」。 */
    data class MergeResult(val updated: Int, val added: Int) {
        val total: Int get() = updated + added
    }

    /** 导出全部观看记录。文件很小（只有标题/封面/进度），适合随手分享或丢进网盘。 */
    suspend fun exportWatchHistory(context: Context, uri: Uri) {
        val items = HistoryDatabase.instance.watchHistory.getAll()
        val payload = json.encodeToString(ProgressFile(items = items))
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(payload)
        } ?: error("Unable to open output file")
    }

    /** 合并导入。返回实际改动条数；一条都没改动时 `total == 0`。 */
    suspend fun importWatchHistoryMerge(context: Context, uri: Uri): MergeResult {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Unable to open input file")
        val file = runCatching { json.decodeFromString<ProgressFile>(text) }.getOrNull()
            // 兼容「直接把完整备份文件丢进来」的情况：完整备份的字段更多，
            // 但只要 ignoreUnknownKeys 能解出 watchHistories 就够了。
            ?: decodeFromFullBackup(text)
            ?: error("Unrecognized file")

        val dao = HistoryDatabase.instance.watchHistory
        var updated = 0
        var added = 0

        file.items
            .filter { it.videoCode.isNotBlank() }
            .forEach { incoming ->
                val existing = dao.findBy(incoming.videoCode)
                if (existing == null) {
                    // id 必须清 0 交给 Room 自增，否则会和现有记录的主键撞车（REPLACE 会误删一条）。
                    dao.insert(incoming.copy(id = 0, watchDate = normalized(incoming.watchDate)))
                    added++
                } else {
                    val merged = merge(existing, incoming)
                    if (merged != existing) {
                        dao.update(merged)
                        updated++
                    }
                }
            }

        return MergeResult(updated = updated, added = added)
    }

    private fun merge(existing: WatchHistoryEntity, incoming: WatchHistoryEntity): WatchHistoryEntity {
        val existingAt = normalized(existing.watchDate)
        val incomingAt = normalized(incoming.watchDate)
        val incomingIsNewer = incomingAt >= existingAt
        val newer = if (incomingIsNewer) incoming else existing
        return existing.copy(
            // 进度取大：正在看的进度不会被一条更旧的记录回退。
            progress = maxOf(existing.progress, incoming.progress),
            watchDate = maxOf(existingAt, incomingAt),
            // 元信息跟更新的那条走，但旧记录字段为空时不要用空的覆盖掉非空的。
            coverUrl = newer.coverUrl.ifBlank { existing.coverUrl },
            title = newer.title.ifBlank { existing.title },
            releaseDate = if (newer.releaseDate != 0L) newer.releaseDate else existing.releaseDate,
        )
    }

    private fun normalized(ts: Long): Long =
        if (ts in 1 until SECONDS_UPPER_BOUND) ts * 1000 else ts

    /**
     * 从完整备份文件里捞出观看记录。
     *
     * 用户很可能直接把「导出备份」产出的那个文件丢进来 —— 期望是能用的。
     * 这里只解析我们关心的那一个字段，其余（下载记录、设置）一概忽略，
     * 保持本通道「只动观看记录」的语义。
     */
    private fun decodeFromFullBackup(text: String): ProgressFile? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val element = root["watchHistories"] ?: return null
        val items = runCatching {
            json.decodeFromJsonElement<List<WatchHistoryEntity>>(element)
        }.getOrNull() ?: return null
        return ProgressFile(items = items)
    }
}
