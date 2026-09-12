package io.github.daisukikaffuchino.han1meviewer.logic.njav

import io.github.daisukikaffuchino.han1meviewer.HanimeLink
import io.github.daisukikaffuchino.han1meviewer.ResolutionLinkMap
import io.github.daisukikaffuchino.han1meviewer.logic.exception.ParseException
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.VideoLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import kotlinx.datetime.LocalDate
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * nJAV（njavtv.com）页面解析。
 *
 * 这里的产物**刻意复用 hanime 的模型**（[HomePage] / [HanimeInfo] / [HanimeVideo]），
 * 这样 ViewModel 与整个 Compose UI 一行都不用改，只是在 [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo]
 * 里按当前数据源分流而已。
 *
 * 列表页的卡片结构（2026-09 实测）：
 *
 * ```html
 * <div class="thumbnail group">
 *   <div class="relative …">
 *     <a href="https://njavtv.com/pppe-440" alt="pppe-440">
 *       <video data-src="https://fourhoi.com/pppe-440/preview.mp4"></video>
 *       <img class="lozad w-full" data-src="https://fourhoi.com/pppe-440/cover-t.jpg" …>
 *     </a>
 *     <a href="…"><span class="absolute bottom-1 right-1 …">1:58:48</span></a>
 *   </div>
 *   <div class="my-2 …"><a href="…">PPPE-440 标题 - 女优</a></div>
 * </div>
 * ```
 *
 * ⚠️ 卡片的 `href` 有**三种写法**并存，全都靠 `substringAfterLast('/')` 取尾部 slug，
 * 所以这里不用改；但**拼详情页地址时必须走裸 slug**（见 [NjavNetwork.detailUrl]）：
 *
 * ```
 * https://njavtv.com/pppe-440              ← 规范形式（当前主流）
 * https://njavtv.com/dm75/waaa-214         ← 随机数字前缀，会变，不能照抄
 * https://njavtv.com/npjs-158-uncensored-leak
 * ```
 *
 * 详情页的字段全在 `og:` 系列 meta 里，播放地址见 [NjavPacker]。
 */
object NjavParser {

    /**
     * 番号 slug：字母开头、中间必含「-数字」（如 `venx-381`、`ssni-429-uncensored-leak`、`fc2-ppv-3668755`）。
     * 用来把「影片卡片」和「导航链接 / 女优页 / 分类页」区分开。
     */
    private val VIDEO_SLUG = Regex("""^[a-z]+-*\d[a-z0-9-]*$""")

    /** 已知的非影片路径，尾部再兜一层。 */
    private val NON_VIDEO_PATHS = setOf(
        "actresses", "genres", "makers", "new", "release", "uncensored-leak",
        "chinese-subtitle", "today-hot", "weekly-hot", "monthly-hot", "search",
        "legacy", "login", "register", "history", "playlists", "saved", "upload",
        "terms", "contact", "ads", "clive", "klive", "cn", "en", "ja", "ko",
    )

    /** 首页要抓的栏目：[键] 对应用 [homePage] 填入 [HomePage] 的哪个槽位。 */
    const val SEC_LATEST_AV = "latest_av"
    const val SEC_LATEST_RELEASE = "latest_release"
    const val SEC_UNCENSORED = "uncensored"
    const val SEC_CHINESE_SUBTITLE = "chinese_subtitle"
    const val SEC_WEEKLY_HOT = "weekly_hot"
    const val SEC_TODAY_HOT = "today_hot"
    const val SEC_MONTHLY_HOT = "monthly_hot"

    /** 首页栏目 → nJAV 路径（已全部实测可达，每页 12 条）。 */
    val HOME_SECTIONS: List<Pair<String, String>> = listOf(
        SEC_LATEST_AV to "new",
        SEC_LATEST_RELEASE to "release",
        SEC_UNCENSORED to "uncensored-leak",
        SEC_CHINESE_SUBTITLE to "chinese-subtitle",
        SEC_WEEKLY_HOT to "weekly-hot",
        SEC_TODAY_HOT to "today-hot",
        SEC_MONTHLY_HOT to "monthly-hot",
    )

    /**
     * [io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.buildCategoryList]
     * 在 AV 模式下给每个分类打的「检索标记」→ nJAV 路径。
     * 用户点某个分类的「更多」时，仓库层就是靠这张表把标记翻译成 nJAV 的分类页。
     */
    private val MARKER_TO_PATH = mapOf(
        "日本AV" to "new",
        "最新上市" to "release",
        "高清無碼" to "uncensored-leak",
        "中文字幕" to "chinese-subtitle",
        "他們在看" to "weekly-hot",
        "本日排行" to "today-hot",
        "本月排行" to "monthly-hot",
    )

    fun pathForMarker(marker: String?): String? =
        marker?.trim()?.takeIf { it.isNotEmpty() }?.let { MARKER_TO_PATH[it] }

    //<editor-fold desc="列表">

    /** 解析列表页（首页 / 分类页 / 搜索页共用同一套卡片结构）。 */
    fun videoList(body: String): MutableList<HanimeInfo> {
        val doc = Jsoup.parse(body)
        val result = LinkedHashMap<String, HanimeInfo>()
        doc.select("div.thumbnail.group").forEach { card ->
            parseCard(card)?.let { result.putIfAbsent(it.videoCode, it) }
        }
        return result.values.toMutableList()
    }

    fun pageState(body: String): PageLoadingState<MutableList<HanimeInfo>> {
        val list = videoList(body)
        return when {
            list.isNotEmpty() -> PageLoadingState.Success(list)
            hasNextPage(body) -> PageLoadingState.Success(list)
            else -> PageLoadingState.NoMoreData
        }
    }

    /** 列表页底部是否有 `rel="next"` 的「下一页」。 */
    fun hasNextPage(body: String): Boolean =
        Jsoup.parse(body).selectFirst("a[rel=next]") != null

    private fun parseCard(card: Element): HanimeInfo? {
        val slug = card.select("a[href]")
            .asSequence()
            .map { anchor ->
                anchor.attr("href").substringAfterLast('/').substringBefore('?').substringBefore('#')
            }
            .firstOrNull { it.isNotBlank() && it !in NON_VIDEO_PATHS && VIDEO_SLUG.matches(it) }
            ?: return null

        // 标题在卡片底部那个 div.my-2 的 <a> 里；退化时用 alt（番号）兜底。
        val title = card.selectFirst("div.my-2 a")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("a[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
            ?: slug.uppercase()

        val cover = card.selectFirst("img[data-src]")?.attr("data-src")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "https://fourhoi.com/$slug/cover-t.jpg"

        val duration = parseDuration(card)

        return HanimeInfo(
            title = title,
            coverUrl = cover,
            videoCode = slug,
            duration = duration,
            itemType = HanimeInfo.NORMAL,
        )
    }

    /**
     * 卡片右下角的时长。
     *
     * ⚠️ **不能**直接 `selectFirst("span.absolute")`。卡片里有两个 `span.absolute`：
     *
     * ```html
     * <span class="absolute bottom-1 left-1 …bg-red-800…">中文字幕</span>   ← 角标，DOM 里在前
     * <span class="absolute bottom-1 right-1 …bg-gray-800…">2:45:03</span>  ← 真正的时长
     * ```
     *
     * 中文字幕 / 无码 分类页的卡片都带左侧角标，于是「取第一个」拿到的就是角标，
     * 表现为**右下角时长显示成「中文字幕」**（用户 2026-09-12 报的那个 bug）。
     *
     * 修法：不按位置猜，按**内容**挑 —— 只认长得像时长的那个（`1:58:48` / `12:34`）。
     * 顺带也把左侧角标的文案丢掉了，反正 [HanimeInfo] 没有装它的字段。
     */
    private fun parseDuration(card: Element): String? = card.select("span.absolute")
        .asSequence()
        .map { it.text().trim() }
        .firstOrNull { DURATION_TEXT.matches(it) }

    /** `1:58:48` / `12:34`；见 [parseDuration]。 */
    private val DURATION_TEXT = Regex("""^\d{1,3}:\d{2}(?::\d{2})?$""")

    /** 把若干栏目拼成首页模型；空栏目会被 [io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.buildCategoryList] 自动过滤掉。 */
    fun homePage(sections: Map<String, List<HanimeInfo>>): WebsiteState<HomePage> {
        fun list(key: String) = sections[key].orEmpty().toMutableList()
        return WebsiteState.Success(
            HomePage(
                csrfToken = null,
                avatarUrl = null,
                username = null,
                banner = null,
                latestHanime = mutableListOf(),
                latestRelease = list(SEC_LATEST_RELEASE),
                ecchiAnime = list(SEC_LATEST_AV),
                shortEpisodeAnime = mutableListOf(),
                twoPointFiveDAnime = mutableListOf(),
                threeDCG = mutableListOf(),
                motionAnime = list(SEC_UNCENSORED),
                twoDAnime = mutableListOf(),
                aiGenerated = list(SEC_CHINESE_SUBTITLE),
                mmd = list(SEC_TODAY_HOT),
                cosplay = list(SEC_MONTHLY_HOT),
                watchingNow = list(SEC_WEEKLY_HOT),
                newAnimeTrailer = mutableListOf(),
                userId = "",
            )
        )
    }

    //</editor-fold>

    //<editor-fold desc="详情">

    fun video(body: String): VideoLoadingState<HanimeVideo> {
        val doc = Jsoup.parse(body)

        fun meta(property: String): String? = doc.selectFirst("meta[property=$property]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val title = meta("og:title")
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return VideoLoadingState.Error(ParseException("nJAV：未能解析影片标题"))

        val videoUrls = buildVideoUrls(NjavPacker.extractM3u8(body))
        if (videoUrls.isEmpty()) {
            return VideoLoadingState.Error(ParseException("nJAV：未能解析播放地址"))
        }

        val tags = doc.selectFirst("meta[name=keywords]")?.attr("content")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()

        return VideoLoadingState.Success(
            HanimeVideo(
                title = title,
                coverUrl = meta("og:image").orEmpty(),
                chineseTitle = null,
                introduction = meta("og:description"),
                uploadTime = meta("og:video:release_date")?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                },
                videoUrls = videoUrls,
                tags = tags,
            )
        )
    }

    /**
     * nJAV 的 `<source>` 一般只有一份自适应主列表（`playlist.m3u8`）加若干固定码率。
     * ⚠️ 顺序有讲究：播放器在「用户偏好清晰度不存在」时会退回**最后一项**，
     * 所以自适应的主列表必须放最后，普通用户默认就能拿到最好的画质。
     */
    private fun buildVideoUrls(urls: List<String>): ResolutionLinkMap {
        val master = urls.firstOrNull { it.endsWith("/playlist.m3u8", ignoreCase = true) }
        val map = linkedMapOf<String, HanimeLink>()
        urls.filter { it != master }
            .distinct()
            .forEach { url ->
                val label = qualityLabel(url)
                if (label !in map) map[label] = HanimeLink(url, HLS_SUBTYPE)
            }
        master?.let { map["自动"] = HanimeLink(it, HLS_SUBTYPE) }
        return map
    }

    /**
     * nJAV 给的全是 HLS 清单，下载后拼出来的是 MPEG-TS。
     *
     * [HanimeLink.subtype] = `mp2t` 会让 [HanimeLink.suffix] 返回 `ts`，
     * 于是下载文件叫 `…_1080P.ts`。**这不是可有可无的**：真实字节是 TS，
     * 挂个 `.mp4` 后缀会让部分播放器按容器名去解析而失败。
     */
    private const val HLS_SUBTYPE = "mp2t"

    /**
     * 从播放地址里抽清晰度标签。
     *
     * 站点有两代路径写法，都要能吃下（2026-09 实测两者并存）：
     *
     * ```
     * https://surrit.com/<uuid>/720p/video.m3u8        ← 现行
     * https://surrit.com/<uuid>/1280x720/video.m3u8    ← 另一种
     * ```
     *
     * 注意 `1280x720` 里的 `720` 是**高**，`842x480` 里的 `480` 也是高 ——
     * 统一取高度当标签（`720P` / `480P`），跟 hanime 侧的
     * [io.github.daisukikaffuchino.han1meviewer.HanimeResolution] 命名保持一致。
     *
     * ⚠️ 别再写回 `/(\d{3,4})p/` 这种只认一种写法的正则：一旦站点换成
     * `1280x720` 形式，所有条目都会掉进「其他」，而 [buildVideoUrls] 里的
     * 去重是「同标签只留第一个」，于是清晰度列表会**只剩一项**。
     */
    private fun qualityLabel(url: String): String {
        val groups = RESOLUTION_IN_URL.find(url)?.groupValues ?: return UNKNOWN_QUALITY_LABEL
        // 1=宽、2=高（`1280x720`）；3=高（`720p`，另一种写法里 1、2 参与不到匹配，是空串）
        val height = groups.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: groups.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: groups.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return UNKNOWN_QUALITY_LABEL
        return "${height}P"
    }

    private val RESOLUTION_IN_URL = Regex("""/(?:(\d{3,4})x(\d{3,4})|(\d{3,4})p)/""", RegexOption.IGNORE_CASE)

    /** 认不出清晰度时的兜底标签。 */
    private const val UNKNOWN_QUALITY_LABEL = "其他"

    //</editor-fold>

    /**
     * nJAV 没有 hanime 那种「本月新番预告」页，日历里也就没有对应的月度数据。
     * 这里返回一个**空但合法**的预告对象，让日历页展示空态而不是报错。
     */
    fun emptyPreview(): WebsiteState<HanimePreview> = WebsiteState.Success(
        HanimePreview(
            headerPicUrl = null,
            hasPrevious = false,
            hasNext = false,
            latestHanime = emptyList(),
            previewInfo = emptyList(),
        )
    )
}
