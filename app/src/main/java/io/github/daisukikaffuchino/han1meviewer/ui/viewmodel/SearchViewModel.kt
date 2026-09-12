package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import android.os.Parcelable
import io.github.daisukikaffuchino.utils.LogUtil
import android.util.SparseArray
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo.HanimeAdvancedSearchRepo.toSearchOptionSet
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.entity.HanimeAdvancedSearchHistoryEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.SearchHistoryEntity
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.SearchOption
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.utils.loadAssetAs
import io.github.daisukikaffuchino.utils.unsafeLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/13 013 22:29
 */
class SearchViewModel(
    private val state: SavedStateHandle
) : ViewModel() {

    var page: Int = 1
    var query: String?
        get() = state["query"]
        set(value) { state["query"] = value }

    var genre: String?
        get() = state["genre"]
        set(value) { state["genre"] = value }

    var sort: String?
        get() = state["sort"]
        set(value) { state["sort"] = value }

    var year: Int?
        get() = state["year"]
        set(value) { state["year"] = value }

    var month: Int?
        get() = state["month"]
        set(value) { state["month"] = value }

    var approxTime: String?
        get() = state["approxTime"]
        set(value) { state["approxTime"] = value }

    var broad: Boolean
        get() = state["broad"] ?: false
        set(value) { state["broad"] = value }

    var duration: String?
        get() = state["duration"]
        set(value) { state["duration"] = value }

    var gridFirstVisibleItemIndex: Int
        get() = state["gridFirstVisibleItemIndex"] ?: 0
        set(value) {
            state["gridFirstVisibleItemIndex"] = value
        }

    var gridFirstVisibleItemScrollOffset: Int
        get() = state["gridFirstVisibleItemScrollOffset"] ?: 0
        set(value) {
            state["gridFirstVisibleItemScrollOffset"] = value
        }

    var tagMap = SparseArray<Set<SearchOption>>()
    var brandMap = SparseArray<Set<SearchOption>>()

    /**
     * nJAV 专属：当前选中的女优，存的是
     * [io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress.path]
     * （形如 `actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3`）。
     * 非 nJAV 数据源下恒为 null。
     */
    var actressPath: String?
        get() = state["actressPath"]
        set(value) { state["actressPath"] = value }

    /** nJAV 专属：选中女优的显示名，只用来画 chip 上的文案。 */
    var actressName: String?
        get() = state["actressName"]
        set(value) { state["actressName"] = value }

    /**
     * 选中一位女优，并**把互相打架的其它条件一并清掉**。
     *
     * nJAV 的女优页（`/cn/actresses/<name>`）是一个独立的列表页，站方不接受
     * 「女优 + 关键词 / 分类 / 标签」这种组合。与其在界面上留一堆点了不生效的 chip，
     * 不如选中女优时清空它们 —— **界面显示什么，结果就是什么**。
     */
    fun applyActressFilter(path: String, name: String) {
        query = null
        genre = null
        sort = null
        year = null
        month = null
        approxTime = null
        duration = null
        broad = false
        tagMap.clear()
        brandMap.clear()
        actressPath = path
        actressName = name
    }

    fun clearActressFilter() {
        actressPath = null
        actressName = null
    }

    val genres by unsafeLazy {
        loadAssetAs<List<SearchOption>>(
            if (SettingsRepository.isNjavSite) "search_options/genre_av.json"
            else "search_options/genre.json"
        ).orEmpty()
    }

    val tags by unsafeLazy {
        loadAssetAs<Map<String, List<SearchOption>>>("search_options/tags.json").orEmpty()
    }

    val brands by unsafeLazy {
        loadAssetAs<List<SearchOption>>("search_options/brands.json").orEmpty()
    }

    val sortOptions by unsafeLazy {
        loadAssetAs<List<SearchOption>>("search_options/sort_option.json").orEmpty()
    }

    val durations by unsafeLazy {
        loadAssetAs<List<SearchOption>>("search_options/duration.json").orEmpty()
    }
    val timeList by unsafeLazy {
        loadAssetAs<List<SearchOption>>("search_options/release_date.json").orEmpty()
    }

    private val _searchStateFlow =
        MutableStateFlow<PageLoadingState<List<HanimeInfo>>>(PageLoadingState.Loading)
    val searchStateFlow = _searchStateFlow.asStateFlow()

    private val _searchFlow = MutableStateFlow(emptyList<HanimeInfo>())
    val searchFlow = _searchFlow.asStateFlow()
    var recyclerViewState: Parcelable? = null

    fun clearHanimeSearchResult() {
        _searchFlow.value = emptyList()
        _searchStateFlow.value = PageLoadingState.Loading
    }

    fun resetSearchUiState() {
        page = 1
        query = null
        genre = null
        sort = null
        year = null
        month = null
        approxTime = null
        broad = false
        duration = null
        tagMap.clear()
        brandMap.clear()
        actressPath = null
        actressName = null
        recyclerViewState = null
        gridFirstVisibleItemIndex = 0
        gridFirstVisibleItemScrollOffset = 0
        _searchFlow.value = emptyList()
        _searchStateFlow.value = PageLoadingState.Loading
    }

    fun getHanimeSearchResult(
        page: Int, query: String?, genre: String?,
        sort: String?, broad: Boolean, date: String?,
        duration: String?, tags: Set<String>, brands: Set<String>,
    ) {
        viewModelScope.launch {
            NetworkRepo.getHanimeSearchResult(
                page, query, genre,
                sort, broad, date ,
                duration, tags, brands,
                actressPath,
            ).collect { state ->
                val prev = _searchStateFlow.getAndUpdate { state }
                if (prev is PageLoadingState.Loading) _searchFlow.value = emptyList()
                _searchFlow.update { prevList ->
                    when (state) {
//                        is PageLoadingState.Success -> prevList + state.info
                        is PageLoadingState.Success -> {
                            val list = state.info
                            val updatedList = if (SettingsRepository.showPlayedIndicator) {
                                val codes = list.map { it.videoCode }
                                val watchedCodes = withContext(Dispatchers.IO) {
                                    DatabaseRepo.WatchHistory.getWatched(codes).toSet()
                                }
                                list.map { item ->
                                    item.copy(watched = watchedCodes.contains(item.videoCode))
                                }
                            } else {
                                list
                            }
                            (prevList + updatedList).distinctBy(HanimeInfo::videoCode)
                        }
                        is PageLoadingState.Loading -> emptyList()
                        else -> prevList
                    }
                }
            }
        }
    }

    fun insertSearchHistory(history: SearchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.SearchHistory.insert(history)
            LogUtil.d("insert_search_hty", "$history DONE!")
        }
    }

    fun getSearchDate(): String? {
        return when {
            approxTime != null -> approxTime
            year != null -> listOfNotNull(
                year?.let { "$it 年" },
                month?.let { "$it 月" }
            ).joinToString(" ")
            else -> null
        }
    }

    fun insertAdvancedSearchHistory(
        query: String?, genre: String?,
        sort: String?, broad: Boolean, date: String?,
        duration: String?, tags: Set<SearchOption>, brands: Set<SearchOption>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val histories = DatabaseRepo.HanimeAdvancedSearchRepo.getSearchHistories(limit = 10)
                .first()

            val isDuplicate = histories.any { history ->
                history.query == query &&
                        history.genre == genre &&
                        history.sort == sort &&
                        history.broad == broad &&
                        history.date == date &&
                        history.duration == duration &&
                        history.tags?.toSearchOptionSet() == tags &&
                        history.brands?.toSearchOptionSet() == brands
            }

            if (!isDuplicate) {
                DatabaseRepo.HanimeAdvancedSearchRepo.saveSearch(
                    query = query,
                    genre = genre,
                    sort = sort,
                    broad = broad,
                    date = date,
                    duration = duration,
                    tags = tags,
                    brands = brands,
                )
                return@launch
            }
            LogUtil.i("insertAdvancedSearchHistory","记录重复！")

        }
    }

    fun deleteSearchHistory(history: SearchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.SearchHistory.delete(history)
            LogUtil.d("delete_search_hty", "$history DONE!")
        }
    }

    @JvmOverloads
    fun loadAllSearchHistories(keyword: String? = null) =
        DatabaseRepo.SearchHistory.loadAll(keyword).flowOn(Dispatchers.IO)

    fun deleteSearchHistoryByKeyword(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.SearchHistory.deleteByKeyword(query)
            LogUtil.d("delete_search_hty", "$query DONE!")
        }
    }
    val refreshTriggerFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun triggerNewSearch() {
        page = 1
        clearHanimeSearchResult()
        refreshTriggerFlow.tryEmit(Unit)
    }
    fun restoreSearchMap(history: HanimeAdvancedSearchHistoryEntity) {
        with(this) {
            page = 1
            query = history.query
            genre = history.genre
            sort = history.sort
            broad = history.broad == true
            duration = history.duration

            restoreDate(this, history.date)

            tagMap.clear()
            brandMap.clear()

            history.tags?.takeIf { it.isNotBlank() }?.let { tagsString ->
                val tagOptions = tagsString.toSearchOptionSet()
                tagMap.put(0, tagOptions)
            }

            history.brands?.takeIf { it.isNotBlank() }?.let { brandsString ->
                val brandOptions = brandsString.toSearchOptionSet()
                brandMap.put(0, brandOptions)
            }
        }
    }
    private fun restoreDate(viewModel: SearchViewModel, date: String?) {
        if (date.isNullOrBlank()) {
            viewModel.year = null
            viewModel.month = null
            viewModel.approxTime = null
            return
        }

        if (date.contains("過去")) {
            viewModel.approxTime = date
            viewModel.year = null
            viewModel.month = null
        } else {
            viewModel.approxTime = null
            val regex = """(\d+)\s*年(?:\s*(\d+)\s*月)?""".toRegex()
            val match = regex.find(date)
            if (match != null) {
                val (y, m) = match.destructured
                viewModel.year = y.toIntOrNull()
                viewModel.month = m.toIntOrNull()
            } else {
                viewModel.year = null
                viewModel.month = null
            }
        }
    }
}
