package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.PreviewArchiveUiState
import io.github.daisukikaffuchino.han1meviewer.util.TagLocalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/23 023 16:47
 */
class PreviewViewModel : ViewModel() {

    private val previewCache = linkedMapOf<String, WebsiteState<HanimePreview>>()

    private val _previewFlow =
        MutableStateFlow<WebsiteState<HanimePreview>>(WebsiteState.Loading)
    val previewFlow = _previewFlow.asStateFlow()

    fun getHanimePreview(date: String) {
        viewModelScope.launch {
            previewCache[date]?.let {
                _previewFlow.value = it
                return@launch
            }
            NetworkRepo.getHanimePreview(date).collect { preview ->
                val localizedPreview = preview.withLocalizedTags()
                _previewFlow.value = localizedPreview
                if (localizedPreview !is WebsiteState.Loading) {
                    previewCache[date] = localizedPreview
                }
            }
        }
    }

    fun preloadPreview(date: String) {
        if (previewCache.containsKey(date)) return
        viewModelScope.launch {
            val preview = runCatching {
                withContext(Dispatchers.IO) {
                    NetworkRepo.getHanimePreview(date)
                        .catch { emit(WebsiteState.Error(it)) }
                        .first { it !is WebsiteState.Loading }
                }
            }.getOrElse { WebsiteState.Error(it) }
            previewCache[date] = preview.withLocalizedTags()
        }
    }

    fun getCachedPreview(date: String): WebsiteState<HanimePreview>? = previewCache[date]

    //<editor-fold desc="月度归档（停更月份）">

    /**
     * 【月度归档】站方预告停更月份（`202605` 起）的内容。
     *
     * 这些月份 `/previews/{yyyyMM}` 整段返回 500，页面改为按上市月份检索
     * （[NetworkRepo.getHanimeArchiveByMonth]），列出该月 1 日至月底上线的全部番剧。
     * 值为 null 表示当前月份不是停更月份，仍走正常的预告流程。
     */
    private val _archiveFlow = MutableStateFlow<PreviewArchiveUiState?>(null)
    val archiveFlow = _archiveFlow.asStateFlow()

    private var archiveJob: Job? = null
    private var archiveYear = 0
    private var archiveMonth = 0
    private var archiveLoadedPage = 0

    /**
     * 加载某个停更月份的归档列表（首屏）。
     *
     * 同一个月份重复调用会被忽略，避免翻月来回切换时反复打请求。
     *
     * @param force 为 true 时强制重新加载（用于"重试"）
     */
    fun loadArchiveMonth(year: Int, month: Int, force: Boolean = false) {
        val sameMonth = archiveYear == year && archiveMonth == month
        val cached = _archiveFlow.value
        if (!force && sameMonth && cached != null && cached.hasItems) return
        if (!force && sameMonth && cached != null && cached.isLoading) return

        archiveYear = year
        archiveMonth = month
        archiveLoadedPage = 0
        archiveJob?.cancel()
        _archiveFlow.value = PreviewArchiveUiState(isLoading = true)
        archiveJob = viewModelScope.launch { fetchArchivePage(1) }
    }

    /** 加载归档列表的下一页。 */
    fun loadMoreArchive() {
        val current = _archiveFlow.value ?: return
        if (current.isLoading || current.isLoadingMore ||
            current.hasError || current.noMoreData
        ) {
            return
        }
        archiveJob?.cancel()
        _archiveFlow.value = current.copy(isLoadingMore = true)
        archiveJob = viewModelScope.launch { fetchArchivePage(archiveLoadedPage + 1) }
    }

    /** 离开停更月份时清掉归档状态。 */
    fun clearArchive() {
        archiveJob?.cancel()
        archiveJob = null
        archiveYear = 0
        archiveMonth = 0
        archiveLoadedPage = 0
        _archiveFlow.value = null
    }

    private suspend fun fetchArchivePage(page: Int) {
        NetworkRepo.getHanimeArchiveByMonth(archiveYear, archiveMonth, page)
            .collect { state ->
                val prev = _archiveFlow.value ?: PreviewArchiveUiState()
                when (state) {
                    is PageLoadingState.Success -> {
                        val pageItems: List<HanimeInfo> = state.info
                        val merged = if (page <= 1) {
                            pageItems
                        } else {
                            (prev.items + pageItems).distinctBy(HanimeInfo::videoCode)
                        }
                        archiveLoadedPage = page
                        _archiveFlow.value = prev.copy(
                            items = merged,
                            isLoading = false,
                            isLoadingMore = false,
                            hasError = false,
                            noMoreData = pageItems.isEmpty(),
                            loadedPages = page,
                        )
                    }

                    PageLoadingState.NoMoreData -> {
                        archiveLoadedPage = page
                        _archiveFlow.value = prev.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            hasError = false,
                            noMoreData = true,
                            loadedPages = page,
                        )
                    }

                    is PageLoadingState.Error -> {
                        _archiveFlow.value = prev.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            hasError = true,
                        )
                    }

                    PageLoadingState.Loading -> Unit
                }
            }
    }

    //</editor-fold>

    private fun WebsiteState<HanimePreview>.withLocalizedTags(): WebsiteState<HanimePreview> {
        return if (this is WebsiteState.Success) {
            WebsiteState.Success(info.withLocalizedTags())
        } else {
            this
        }
    }

    private fun HanimePreview.withLocalizedTags(): HanimePreview {
        return copy(previewInfo = previewInfo.map { info ->
            info.copy(tags = TagLocalizer.localizeTags(info.tags))
        })
    }
}
