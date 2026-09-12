package io.github.daisukikaffuchino.han1meviewer.logic

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 关键词搜索历史的管理：置顶、清空、容量上限。
 *
 * ## 为什么有了 DAO 还要这一层
 *
 * [io.github.daisukikaffuchino.han1meviewer.logic.dao.SearchHistoryDao] 只提供
 * 「按输入顺序列出来」和「删一条」。但实际用起来缺三件事：
 *
 * 1. **常用搜索会被新搜索顶下去。** 查得最频繁的那几个词，反而因为最近没搜而排到末尾。
 *    → 置顶（存在设置里，不动表结构）。
 * 2. **历史无上限。** 用久了会拖成几百条，界面没变慢但可读性没了。
 *    → [prune] 按上限裁掉最旧的，**置顶的不裁**。
 * 3. **只能一条条删。** → [clearAll]。
 *
 * 置顶放在设置（JSON 数组）而不是给表加一列：为它加列就要写一次 Room 迁移，
 * 而它本质只是「顺序」，不是数据。
 */
object SearchHistoryManager {

    /** 保留的普通历史条数上限。置顶的**不占**这个额度。 */
    const val HISTORY_LIMIT = 50

    /** 置顶词条数上限。 */
    private const val PIN_LIMIT = 20

    private val json = Json { ignoreUnknownKeys = true }

    /** 当前置顶的搜索词，按置顶顺序（新的在前）。 */
    fun pinned(): List<String> =
        runCatching { json.decodeFromString<List<String>>(SettingsRepository.pinnedSearchesJson) }
            .getOrDefault(emptyList())

    fun isPinned(query: String): Boolean =
        pinned().any { it.equals(query.trim(), ignoreCase = true) }

    /** 置顶 / 取消置顶。 */
    suspend fun togglePin(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val current = pinned()
        val next = if (current.any { it.equals(q, ignoreCase = true) }) {
            current.filterNot { it.equals(q, ignoreCase = true) }
        } else {
            (listOf(q) + current).take(PIN_LIMIT)
        }
        save(next)
    }

    /**
     * 把历史排成「置顶优先、其余保持原有顺序」。
     *
     * 置顶的若已不在历史里（被裁掉或手动删过）就不插入 —— 否则会出现一条
     * 点进去没有实际历史记录的幽灵项。
     */
    fun sortByPinned(queries: List<String>): List<String> {
        val pins = pinned()
        if (pins.isEmpty()) return queries
        val index = queries.associateBy({ it.lowercase() }, { it })
        val pinnedPresent = pins.mapNotNull { index[it.lowercase()] }
        val pinKeys = pinnedPresent.mapTo(mutableSetOf()) { it.lowercase() }
        return pinnedPresent + queries.filterNot { it.lowercase() in pinKeys }
    }

    /** 清空全部历史（含置顶 —— 用户点「清空」时不会期望还留着几条）。 */
    suspend fun clearAll() {
        runCatching { DatabaseRepo.SearchHistory.clearAll() }
        save(emptyList())
    }

    /**
     * 按 [HISTORY_LIMIT] 裁剪最旧的历史，置顶的词不会被删。
     *
     * 在每次写入历史后调用。失败静默：裁剪是维护性动作，不该因为它失败而让搜索报错。
     */
    suspend fun prune() {
        runCatching {
            val all = DatabaseRepo.SearchHistory.loadAll().first() // 已按 id DESC（新→旧）
            if (all.size <= HISTORY_LIMIT) return
            val pins = pinned().mapTo(mutableSetOf()) { it.lowercase() }
            all.drop(HISTORY_LIMIT)
                .filterNot { it.query.lowercase() in pins }
                .forEach { DatabaseRepo.SearchHistory.delete(it) }
        }
    }

    private suspend fun save(list: List<String>) {
        SettingsRepository.update {
            it.copy(pinnedSearchesJson = json.encodeToString(list))
        }
    }
}
