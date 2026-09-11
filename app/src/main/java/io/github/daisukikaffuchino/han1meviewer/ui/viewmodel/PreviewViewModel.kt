package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.util.TagLocalizer
import kotlinx.coroutines.Dispatchers
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

    /**
     * 【额外增加的一条路】站方最后更新过、目前仍然在线的那一期预告。
     *
     * 与 [previewFlow] 相互独立：它不参与标题、月份翻页和评论，只在
     * 用户请求的月份已经停更、拿不到内容时，往页面上补一块真实存在的内容。
     */
    private val _fallbackFlow =
        MutableStateFlow<WebsiteState<HanimePreview>?>(null)
    val fallbackFlow = _fallbackFlow.asStateFlow()

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

    /**
     * 拉取"站方最后更新过的那一期"预告，供页面作为额外内容展示。
     *
     * 走的是带月份回退的 [NetworkRepo.getHanimePreviewWithFallback]，
     * 请求月份本身就有数据时，返回的 `actualDate` 会等于请求月份
     * （[HanimePreview.isFellBack] 为 false），此时调用方不应重复展示。
     */
    fun getLatestAvailablePreview(fromDate: String) {
        viewModelScope.launch {
            _fallbackFlow.value = WebsiteState.Loading
            NetworkRepo.getHanimePreviewWithFallback(fromDate).collect { preview ->
                _fallbackFlow.value = preview.withLocalizedTags()
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
