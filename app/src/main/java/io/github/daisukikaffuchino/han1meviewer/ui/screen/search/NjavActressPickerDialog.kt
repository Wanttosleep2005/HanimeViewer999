package io.github.daisukikaffuchino.han1meviewer.ui.screen.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.ui.component.IconButton
import io.github.daisukikaffuchino.han1meviewer.ui.component.content.EmptyContent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.rememberRandomLoadingHint
import io.github.daisukikaffuchino.han1meviewer.ui.theme.SpacingNormal
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * nJAV 专用的「女优」筛选选择器。
 *
 * 从 [AdvancedSearchSheet] 上的「女优」chip 打开；选中的女优会写回
 * [io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.SearchViewModel.actressPath]，
 * 之后搜索走 `/cn/actresses/<名字>` 这个独立列表页。
 *
 * ## 为什么是「先加载几页 + 本地过滤」而不是「按名字搜索」
 *
 * 站点的女优索引（`/cn/actresses`）**没有名字检索** —— 实测 `?q=` / `?keyword=` /
 * `?name=` / `?search=` 全被忽略，真正生效的只有 `?page` / `?sort` /
 * `?height` / `?cup` / `?age` / `?debut`。而且索引有 **1400+ 页**（每页 24 人），
 * 全量拉完不现实。
 *
 * 好在索引默认按作品数从多到少排，**叫得出名字的女优都挤在前几页**，
 * 所以「滚动加载 + 输入框过滤已加载条目」在真实使用里是够的；
 * 过滤后没有匹配时，底部还留了「加载更多」让用户自己往下捞。
 *
 * ⚠️ 用全屏 [Dialog] 而不是再套一层 ModalBottomSheet：[AdvancedSearchSheet]
 * 本身就是 BottomSheet，嵌套 sheet 在 Material3 里状态很容易打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NjavActressPickerDialog(
    selectedPath: String?,
    onDismiss: () -> Unit,
    onSelect: (NjavActress) -> Unit,
) {
    var loaded by remember { mutableStateOf<List<NjavActress>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var isLoading by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var keyword by rememberSaveable { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current

    val filtered = remember(loaded, keyword) {
        val key = keyword.trim()
        if (key.isEmpty()) loaded else loaded.filter { it.name.contains(key, ignoreCase = true) }
    }

    LaunchedEffect(page) {
        if (!canLoadMore) return@LaunchedEffect
        isLoading = true
        errorMessage = null
        NetworkRepo.getNjavActressIndex(page).collect { state ->
            when (state) {
                is PageLoadingState.Success -> {
                    loaded = (loaded + state.info).distinctBy { it.path }
                    // 这一页一个都没解析出来，说明到底了，别再翻。
                    canLoadMore = state.info.isNotEmpty()
                }

                PageLoadingState.NoMoreData -> canLoadMore = false
                is PageLoadingState.Error -> errorMessage = state.throwable.message
                PageLoadingState.Loading -> Unit
            }
        }
        isLoading = false
    }

    // 滚到底自动续页。⚠️ 过滤结果为空时**不**自动续页：那会变成一路翻到最后一页
    // （1400+ 页），既慢又费流量 —— 这种情况交给底部的「加载更多」按钮。
    LaunchedEffect(gridState, filtered.size, canLoadMore, isLoading) {
        if (!canLoadMore || isLoading || filtered.isEmpty()) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (canLoadMore && !isLoading && last >= filtered.size - 6) page++
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = stringResource(R.string.actress),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.actress_loaded_count, loaded.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(modifier = Modifier
                            .weight(1f)
                            .height(48.dp)) {
                            BasicTextField(
                                value = keyword,
                                onValueChange = { keyword = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 12.dp),
                                decorationBox = { inner ->
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (keyword.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.actress_search_hint),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.actress_filter_local_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )

                when {
                    errorMessage != null && loaded.isEmpty() -> EmptyContent(
                        hint = stringResource(R.string.actress_list_failed),
                        subHint = errorMessage.orEmpty(),
                        picRes = R.drawable.h_chan_sad,
                    )

                    isLoading && loaded.isEmpty() -> LoadingHint()

                    loaded.isEmpty() -> EmptyContent(
                        hint = stringResource(R.string.actress_empty),
                        picRes = R.drawable.h_chan_speechless,
                    )

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(92.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(SpacingNormal),
                        horizontalArrangement = Arrangement.spacedBy(SpacingNormal),
                        verticalArrangement = Arrangement.spacedBy(SpacingNormal),
                    ) {
                        items(filtered, key = { it.path }) { actress ->
                            ActressCard(
                                actress = actress,
                                selected = actress.path == selectedPath,
                                onClick = { onSelect(actress) },
                            )
                        }
                        if (filtered.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                ) {
                                    EmptyContent(
                                        hint = stringResource(R.string.actress_no_match),
                                        picRes = R.drawable.h_chan_speechless,
                                    )
                                }
                            }
                        }
                        if (canLoadMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LoadMoreFooter(
                                    isLoading = isLoading,
                                    onLoadMore = { if (!isLoading) page++ },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingHint() {
    val hint = rememberRandomLoadingHint()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            LoadingIndicator()
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadMoreFooter(isLoading: Boolean, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onLoadMore,
            ) {
                Text(
                    text = stringResource(R.string.actress_load_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ActressCard(
    actress: NjavActress,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.size(68.dp),
        ) {
            AsyncImage(
                model = actress.avatarUrl,
                contentDescription = actress.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = actress.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        actressSubtitle(actress)?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 「5668 部影片 · 2008 出道」；两个字段都可能解析不出来。 */
@Composable
private fun actressSubtitle(actress: NjavActress): String? {
    val parts = listOfNotNull(
        actress.videoCount?.let { stringResource(R.string.actress_video_count, it) },
        actress.debutYear?.let { stringResource(R.string.actress_debut_year, it) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
