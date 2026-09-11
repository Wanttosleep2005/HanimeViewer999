package io.github.daisukikaffuchino.han1meviewer.logic.model

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/24 024 15:05
 */
data class HanimePreview(
    val headerPicUrl: String?,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val latestHanime: List<HanimeInfo>,
    val previewInfo: List<PreviewInfo>,
    /**
     * 调用方原本请求的月份（yyyyMM）。
     */
    val requestedDate: String? = null,
    /**
     * 实际取到数据的月份（yyyyMM）。
     *
     * 由 [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo.getHanimePreviewWithFallback]
     * 填充：站方自 2026-05 起停止更新新番预告，请求月份落在停更区间时会逐月往前回溯到
     * 最近一个真的有内容的月份，此时该值与 [requestedDate] 不同。
     *
     * 它只用来标记"这块内容是哪个月的"，不改变原有以请求月份为准的标题与翻页。
     */
    val actualDate: String? = null,
) {
    /**
     * 是否发生了月份回退（请求的月份没有内容，展示的是更早月份的数据）。
     */
    val isFellBack: Boolean
        get() = actualDate != null && requestedDate != null && actualDate != requestedDate

    data class PreviewInfo(
        val title: String?,
        val videoTitle: String?,
        val coverUrl: String?,
        val introduction: String?,
        val brand: String?,
        val releaseDate: String?,
        val videoCode: String?,
        val tags: List<String>,
        val relatedPicsUrl: List<String>,
    )
}
