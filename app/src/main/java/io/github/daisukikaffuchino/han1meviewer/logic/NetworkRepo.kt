package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.EMPTY_STRING
import io.github.daisukikaffuchino.han1meviewer.HANIME_GENRE_ANIME
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository.isAlreadyLogin
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.exception.CloudflareBlockedException
import io.github.daisukikaffuchino.han1meviewer.logic.exception.HanimeNotFoundException
import io.github.daisukikaffuchino.han1meviewer.logic.exception.IPBlockedException
import io.github.daisukikaffuchino.han1meviewer.logic.exception.ParseException
import io.github.daisukikaffuchino.han1meviewer.logic.model.CommentPlace
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage
import io.github.daisukikaffuchino.han1meviewer.logic.model.ModifiedPlaylistArgs
import io.github.daisukikaffuchino.han1meviewer.logic.model.MyListType
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import io.github.daisukikaffuchino.han1meviewer.logic.model.OnlineWatchHistorySort
import io.github.daisukikaffuchino.han1meviewer.logic.model.VideoCommentArgs
import io.github.daisukikaffuchino.han1meviewer.logic.model.VideoComments
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavParser
import io.github.daisukikaffuchino.han1meviewer.logic.network.HanimeNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.VideoLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.utils.applicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import java.io.File
import javax.net.ssl.SSLHandshakeException

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:38
 */
object NetworkRepo {

    //<editor-fold desc="Hanime">

    fun getHomePage(): Flow<WebsiteState<HomePage>> =
        if (SettingsRepository.isNjavSite) njavHomePageFlow()
        else websiteIOFlow(
            request = { HanimeNetwork.hanimeService.getHomePage(SettingsRepository.homeUrl) },
            action = Parser::homePageVer2
        )

    fun getHanimeSearchResult(
        page: Int, query: String?, genre: String?,
        sort: String?, broad: Boolean, date: String?,
        duration: String?, tags: Set<String>, brands: Set<String>,
        actressPath: String? = null,
    ): Flow<PageLoadingState<MutableList<HanimeInfo>>> =
        if (SettingsRepository.isNjavSite) {
            njavListFlow(
                page = page,
                url = resolveNjavListUrl(page, query, genre, sort, tags, actressPath),
            )
        } else {
            pageIOFlow(
                request = {
                    HanimeNetwork.hanimeService.getHanimeSearchResult(
                        page, query, genre, sort,
                        if (broad) "on" else null,
                        date, duration, tags, brands
                    )
                },
                action = Parser::hanimeSearch
            )
        }

    fun getHanimeVideo(videoCode: String): Flow<VideoLoadingState<HanimeVideo>> =
        if (SettingsRepository.isNjavSite) njavVideoFlow(videoCode)
        else videoIOFlow(
            request = { HanimeNetwork.hanimeService.getHanimeVideo(videoCode) },
            action = Parser::hanimeVideoVer2
        )

    fun getHanimePreview(date: String): Flow<WebsiteState<HanimePreview>> =
        if (SettingsRepository.isNjavSite) {
            // nJAV 没有「新番预告」这种月历页，日历里返回空态而不是报错。
            flowOf(NjavParser.emptyPreview())
        } else {
            websiteIOFlow(
                request = { HanimeNetwork.hanimeService.getHanimePreview(date) },
                action = Parser::hanimePreview
            )
        }

    /**
     * 【月度归档】按「上市月份」检索该月全部上市的番剧。
     *
     * 站方的 `/previews/{yyyyMM}` 只更新到 2026-04，自 `202605` 起整段返回 HTTP 500，
     * 所以停更之后的月份不能再当"预告"来展示。好在搜索接口本身就支持按上市年月筛选，
     * 参数形如 `date=2026 年 8 月`（与 [io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.SearchViewModel.getSearchDate]
     * 拼出来的格式一致）：
     *
     *     /search?sort=最新上市&date=2026 年 8 月
     *
     * 它返回的是**该月已经上市**的番剧，正好用来顶替停更月份的预告 ——
     * 打开 2026/8 列出的就是 8 月 1 日至 8 月底上线的那一批。
     *
     * @param year 年份，如 2026
     * @param month 月份 (1-12)
     * @param page 页码，从 1 开始
     */
    // 【月度归档】站方预告停更月份改用「按上市月份检索」。
    // 注意必须带上 genre = "裏番"（genre.json 里「里番」的 search_key）：
    // 不带 genre 的搜索结果会混进 3D动画 / MMD / Cosplay / AI生成 等其它分类，
    // 而这个页面的标题就是「某月 里番新番列表」，只应记录里番。
    fun getHanimeArchiveByMonth(
        year: Int,
        month: Int,
        page: Int,
    ): Flow<PageLoadingState<MutableList<HanimeInfo>>> =
        if (SettingsRepository.isNjavSite) {
            // nJAV 没有「按上市月份」归档接口，日历在该数据源下只展示空态。
            flowOf<PageLoadingState<MutableList<HanimeInfo>>>(PageLoadingState.NoMoreData)
        } else {
            pageIOFlow(
                request = {
                    HanimeNetwork.hanimeService.getHanimeSearchResult(
                        page = page,
                        genre = HANIME_GENRE_ANIME,
                        sort = "最新上市",
                        date = "$year 年 $month 月",
                    )
                },
                action = Parser::hanimeSearch
            )
        }

    //获取订阅或者可以说是关注列表及它们的更新
    fun getMySubscriptions(page: Int) = websiteIOFlow(
        request = { HanimeNetwork.hanimeService.getMySubscriptions(page) },
        action = Parser::getMySubscriptions
    )
    //</editor-fold>

    //<editor-fold desc="My List">

    fun getMyListItems(userId: String, listType: Any, page: Int) = pageIOFlow(
        request = {
            when (listType) {
                is String ->
                    HanimeNetwork.myListService.getMyListItems(userId, listType, page)

                is MyListType ->
                    HanimeNetwork.myListService.getMyListItems(userId, listType.value, page)

                else ->
                    throw IllegalArgumentException("typeOrId must be String or MyListType")
            }
        },
        action = Parser::myListItems
    )

    fun getMyPlayListItems(page: Int = 1, listCode: String = "0") = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getMyPlayListItems(listCode, page)
        },
        action = Parser::myPlayListItems
    )

    fun getOnlineWatchHistories(
        userId: String,
        sort: OnlineWatchHistorySort,
        page: Int,
    ) = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getOnlineWatchHistories(userId, sort.value, page)
        },
        action = Parser::onlineWatchHistoryItems,
    )

    fun getUserAccountPage(userId: String) = websiteIOFlow(
        request = { HanimeNetwork.myListService.getUserAccountPage(userId) },
        action = Parser::userAccountPage,
    )

    fun updateUserAccountProfile(
        userId: String,
        csrfToken: String?,
        name: String,
        email: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.updateUserAccountProfile(
                userId = userId,
                csrfToken = csrfToken,
                name = name,
                email = email,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun updateUserAccountPassword(
        userId: String,
        csrfToken: String?,
        oldPassword: String,
        newPassword: String,
        newPasswordConfirm: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.updateUserAccountPassword(
                userId = userId,
                csrfToken = csrfToken,
                oldPassword = oldPassword,
                newPassword = newPassword,
                newPasswordConfirm = newPasswordConfirm,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun updateUserAccountAvatar(
        userId: String,
        csrfToken: String?,
        avatarFile: File,
    ) = websiteIOFlow(
        request = {
            val imageRequestBody = avatarFile.asRequestBody("image/jpeg".toMediaType())
            val imagePart = MultipartBody.Part.createFormData(
                "photo",
                avatarFile.name,
                imageRequestBody,
            )
            HanimeNetwork.myListService.updateUserAccountAvatar(
                userId = userId,
                csrfToken = (csrfToken ?: EMPTY_STRING).toRequestBody("text/plain".toMediaType()),
                method = "patch".toRequestBody("text/plain".toMediaType()),
                type = "photo".toRequestBody("text/plain".toMediaType()),
                photo = imagePart,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun deleteOnlineWatchHistory(
        videoCode: String,
        position: Int,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.deleteOnlineWatchHistory(
                videoCode = videoCode,
                csrfToken = csrfToken,
            )
        },
    ) {
        val jsonObject = JSONObject(it)
        val success = jsonObject.optBoolean("success", false)
        if (success) {
            WebsiteState.Success(position)
        } else {
            WebsiteState.Error(IllegalStateException("cannot delete it ?!"))
        }
    }

    fun deleteMyListItems(
        typeOrCode: Any,
        videoCode: String,
        position: Int,
        token: String?,
    ) = websiteIOFlow(
        request = {
            when (typeOrCode) {
                is String ->
                    HanimeNetwork.myListService.deleteMyListItems(
                        typeOrCode, videoCode,
                        csrfToken = token
                    )

                is MyListType ->
                    HanimeNetwork.myListService.deleteMyListItems(
                        typeOrCode.value, videoCode,
                        csrfToken = token
                    )

                else ->
                    throw IllegalArgumentException("typeOrId must be String or MyListType")
            }
        }
    ) { deleteBody ->
        val jsonObject = JSONObject(deleteBody)
        val returnVideoCode = jsonObject.get("video_id").toString()
        if (videoCode == returnVideoCode) {
            return@websiteIOFlow WebsiteState.Success(position)
        }

        return@websiteIOFlow WebsiteState.Error(IllegalStateException("cannot delete it ?!"))
    }

    fun getPlaylists(page: Int, userId: String ) = websiteIOFlow(
        request = { HanimeNetwork.myListService.getPlaylists(userId, page) },
        action = Parser::playlists
    )

    fun addToMyFavVideo(
        videoCode: String,
        likeStatus: Boolean, // false => "": add fav; true => "1": cancel fav;
        currentUserId: String?,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.addToMyFavVideo(
                videoCode, if (likeStatus) "1" else EMPTY_STRING,
                token, currentUserId
            )
        }
    ) {
        LogUtil.d("add_to_fav_body", it)
        return@websiteIOFlow WebsiteState.Success(likeStatus)
    }

    fun rateVideo(
        videoCode: String,
        isPositive: Boolean,
        likeStatus: Boolean,
        unlikeStatus: Boolean,
        likesCount: Int,
        unlikesCount: Int,
        currentUserId: String?,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.rateVideo(
                videoCode = videoCode,
                isPositive = if (isPositive) 1 else 0,
                likeStatus = if (likeStatus) "1" else EMPTY_STRING,
                unlikeStatus = if (unlikeStatus) "1" else EMPTY_STRING,
                likesCount = likesCount,
                unlikesCount = unlikesCount,
                csrfToken = token,
                userId = currentUserId,
            )
        }
    ) {
        LogUtil.d("rate_video_body", it)
        return@websiteIOFlow WebsiteState.Success(isPositive)
    }

    fun createPlaylist(
        videoCode: String,
        title: String,
        description: String,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.createPlaylist(
                csrfToken, videoCode, title, description
            )
        },
        permittedSuccessCode = intArrayOf(500)
    ) {
        LogUtil.d("create_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun addToMyList(
        listCode: String,
        videoCode: String,
        isChecked: Boolean,
        position: Int,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.addToMyList(
                csrfToken, listCode, videoCode, isChecked
            )
        }
    ) {
        LogUtil.d("add_to_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(position)
    }

    fun modifyPlaylist(
        listCode: String,
        title: String,
        description: String,
        delete: Boolean,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.modifyPlaylist(
                listCode, title, description,
                if (delete) "on" else null,
                csrfToken
            )
        },
        permittedSuccessCode = intArrayOf(302)
    ) {
        LogUtil.d("modify_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(
            ModifiedPlaylistArgs(
                title = title, desc = description, isDeleted = delete,
            )
        )
    }

    //</editor-fold>

    //<editor-fold desc="Comment">

    fun getComments(type: String, code: String) = websiteIOFlow(
        request = { HanimeNetwork.commentService.getComments(type, code) },
        action = Parser::comments
    )

    fun getCommentReply(commentId: String) = websiteIOFlow(
        request = { HanimeNetwork.commentService.getCommentReply(commentId) },
        action = Parser::commentReply
    )

    fun postComment(
        csrfToken: String?,
        currentUserId: String,
        targetUserId: String,
        type: String,
        text: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.postComment(
                csrfToken, currentUserId,
                type, targetUserId, text
            )
        }
    ) {
        LogUtil.d("post_comment_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun postCommentReply(
        csrfToken: String?,
        replyCommentId: String,
        text: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.postCommentReply(
                csrfToken, replyCommentId, text
            )
        }
    ) {
        LogUtil.d("post_comment_reply_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun likeComment(
        csrfToken: String?,
        commentPlace: CommentPlace,
        foreignId: String?,
        isPositive: Boolean, // 你選擇的是讚還是踩，1是讚，0是踩
        likeUserId: String?,
        commentLikesCount: Int,
        commentLikesSum: Int,
        likeCommentStatus: Boolean, // 你之前有沒有點過讚，1是0否
        unlikeCommentStatus: Boolean, // 你之前有沒有點過踩，1是0否
        commentPosition: Int, comment: VideoComments.VideoComment,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.likeComment(
                csrfToken, commentPlace.value, foreignId,
                if (isPositive) 1 else 0,
                likeUserId, commentLikesCount, commentLikesSum,
                if (likeCommentStatus) 1 else 0,
                if (unlikeCommentStatus) 1 else 0
            )
        }
    ) {
        LogUtil.d("like_comment_body", it)
        return@websiteIOFlow WebsiteState.Success(
            VideoCommentArgs(
                commentPosition, isPositive, comment
            )
        )
    }

    fun reportComment(
        csrfToken: String?,
        reason: String,
        currentUserId: String?,
        redirectUrl: String,
        reportableType: String?,
        reportableId: String?
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.submitReport(
                userId = currentUserId,
                csrfToken = csrfToken,
                redirectUrl = redirectUrl,
                reportableId = reportableId,
                reportableType = reportableType,
                reason = reason
            )
        },
        action = Parser::reportCommentResponse
    )

    //</editor-fold>

    //<editor-fold desc="Subscription">

    fun subscribeArtist(
        csrfToken: String?,
        userId: String,
        artistId: String,
        // 这里表示目标状态
        status: Boolean,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.subscriptionService.subscribeArtist(
                csrfToken, userId, artistId,
                if (status) "" else "1"
            )
        }
    ) {
        LogUtil.d("subscribe_artist_body", it)
        return@websiteIOFlow WebsiteState.Success(status)
    }

    //</editor-fold>

    //<editor-fold desc="Base">

    fun login(email: String, password: String) = flow {
        emit(WebsiteState.Loading)
        // 首先获取token
        val loginPage = HanimeNetwork.hanimeService.getLoginPage()
        val token = loginPage.body()?.string()?.let(Parser::extractTokenFromLoginPage)
        val req = HanimeNetwork.hanimeService.login(token, email, password)
        if (req.isSuccessful) {
            // 再次获取登录页面，如果失败则返回 cookie
            // 因为登录成功再次访问 login 返回 404，这是判断是否登录成功的方法
            val loginPageAgain = HanimeNetwork.hanimeService.getLoginPage()
            if (loginPageAgain.code() == 404) {
                // Cookie 會返回 XSRF-TOKEN 和 hanime1_session，我們只需要後者
                // 错误的，还需要 remember_web 字段！但我没找到！
                LogUtil.d("login_headers", req.headers().toMultimap().toString())
                emit(WebsiteState.Success(req.headers().values("Set-Cookie")))
            } else {
                emit(WebsiteState.Error(IllegalStateException(getString(R.string.account_or_password_wrong))))
            }
        } else {
            // 雙重保險
            emit(WebsiteState.Error(IllegalStateException(getString(R.string.account_or_password_wrong))))
        }
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    //<editor-fold desc="nJAV (njavtv.com) 数据源">

    /**
     * nJAV 首页：并行抓取若干分类页，再拼成一个 [HomePage]。
     *
     * 不复用 [websiteIOFlow] 是因为它不是「一个请求 → 一个页面」，而是 7 个分类页
     * 合起来才凑成首页。单个分类失败不影响整体（`runCatching` 兜成空列表），
     * 免得一个栏目 502 就让整个首页报错。
     */
    private fun njavHomePageFlow(): Flow<WebsiteState<HomePage>> = flow {
        val sections = coroutineScope {
            NjavParser.HOME_SECTIONS.map { (key, path) ->
                async(Dispatchers.IO) {
                    key to runCatching {
                        val response = NjavNetwork.service.get(NjavNetwork.listUrl(path))
                        if (response.isSuccessful) {
                            NjavParser.videoList(response.body()?.string().orEmpty())
                        } else {
                            throw ParseException("nJAV: HTTP ${response.code()} - $path")
                        }
                    }.getOrDefault(mutableListOf<HanimeInfo>())
                }
            }.awaitAll().toMap()
        }
        emit(NjavParser.homePage(sections))
    }.catch { e ->
        emit(WebsiteState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    /** nJAV 列表页（分类 / 搜索）通用管线。 */
    private fun njavListFlow(
        page: Int,
        url: String,
    ): Flow<PageLoadingState<MutableList<HanimeInfo>>> = flow {
        val response = NjavNetwork.service.get(url)
        if (!response.isSuccessful) {
            throw ParseException("nJAV: HTTP ${response.code()} - $url")
        }
        val body = response.body()?.string().orEmpty()
        val list = NjavParser.videoList(body)
        emit(
            if (list.isEmpty() && !NjavParser.hasNextPage(body)) {
                PageLoadingState.NoMoreData
            } else {
                PageLoadingState.Success(list)
            }
        )
    }.catch { e ->
        emit(PageLoadingState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    private fun njavVideoFlow(videoCode: String): Flow<VideoLoadingState<HanimeVideo>> = flow {
        val response = NjavNetwork.service.get(NjavNetwork.detailUrl(videoCode))
        if (!response.isSuccessful) {
            throw ParseException("nJAV: HTTP ${response.code()} - $videoCode")
        }
        emit(NjavParser.video(response.body()?.string().orEmpty()))
    }.catch { e ->
        emit(VideoLoadingState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 把 hanime 风格的检索条件翻译成 nJAV 的列表 URL。
     *
     * - 选了女优 → 女优影片列表页 `/{locale}/actresses/{name}`
     * - 有关键词 → 搜索页 `/{locale}/search/{kw}`
     * - 否则按首页分类点击时带下来的「检索标记」（genre / tags / sort）映射到对应分类页
     * - 都没有 → 兜底到「最新」
     *
     * ⚠️ **女优优先级最高**：站点的女优页是一个独立的列表页，关键词 / 分类 / 标签
     * 都拼不进去。所以选中女优时 UI 会把其它条件一并清掉（见
     * [io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.SearchViewModel.applyActressFilter]），
     * 这里的顺序只是兜底，避免出现「点进去却是别的结果」。
     */
    private fun resolveNjavListUrl(
        page: Int,
        query: String?,
        genre: String?,
        sort: String?,
        tags: Set<String>,
        actressPath: String? = null,
    ): String {
        actressPath?.takeIf { it.isNotBlank() }
            ?.let { return NjavNetwork.actressUrl(it, page) }

        val keyword = query?.trim().orEmpty()
        if (keyword.isNotEmpty()) return NjavNetwork.searchUrl(keyword, page)

        val path = sequenceOf(genre).plus(tags.asSequence()).plus(sequenceOf(sort))
            .firstNotNullOfOrNull { NjavParser.pathForMarker(it) }

        return NjavNetwork.listUrl(path ?: "new", page)
    }

    /**
     * nJAV 女优索引（`/cn/actresses`）的第 [page] 页。
     *
     * 站点**不支持在索引页按名字检索**（`?q=` / `?keyword=` / `?name=` 实测都被忽略），
     * 所以这里只负责「按页拉取」，名字过滤交给
     * [io.github.daisukikaffuchino.han1meviewer.ui.screen.search.NjavActressPickerDialog]
     * 在已加载的条目上做。索引默认按作品数从多到少排，**叫得出名字的女优都在前几页**，
     * 所以「先加载几页 + 本地过滤」实际够用。
     */
    fun getNjavActressIndex(page: Int): Flow<PageLoadingState<MutableList<NjavActress>>> = flow {
        val url = NjavNetwork.actressIndexUrl(page)
        val response = NjavNetwork.service.get(url)
        if (!response.isSuccessful) {
            throw ParseException("nJAV: HTTP ${response.code()} - $url")
        }
        val body = response.body()?.string().orEmpty()
        val list = NjavParser.actressList(body)
        emit(
            if (list.isEmpty() && !NjavParser.hasNextPage(body)) {
                PageLoadingState.NoMoreData
            } else {
                PageLoadingState.Success(list)
            }
        )
    }.catch { e ->
        emit(PageLoadingState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    //</editor-fold>

    /**
     * 用于单网页的情况
     *
     * @param permittedSuccessCode 用于处理特殊情况，比如[NetworkRepo.modifyPlaylist]需要302成功
     */
    private fun <T> websiteIOFlow(
        request: suspend () -> Response<ResponseBody>,
        permittedSuccessCode: IntArray? = null,
        action: (String) -> WebsiteState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        val permitted = permittedSuccessCode?.contains(requestResult.code()) == true
        if ((permitted || requestResult.isSuccessful)) {
            emit(action.invoke(resultBody ?: EMPTY_STRING))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于有page分页的情况
     */
    private fun <T> pageIOFlow(
        request: suspend () -> Response<ResponseBody>,
        action: (String) -> PageLoadingState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        if (requestResult.isSuccessful && resultBody != null) {
            emit(action.invoke(resultBody))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(PageLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于影片界面
     */
    private fun <T> videoIOFlow(
        request: suspend () -> Response<ResponseBody>,
        action: (String) -> VideoLoadingState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        if (requestResult.isSuccessful && resultBody != null) {
            emit(action.invoke(resultBody))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(VideoLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    internal fun Response<ResponseBody>.throwRequestException(): Nothing {
        val body = errorBody()?.string()
        when (val code = code()) {
            403 -> if (!body.isNullOrBlank()) {
                when {
                    "you have been blocked" in body ->
                        throw IPBlockedException(getString(R.string.cloudflare_ip_block_warning))

                    "Just a moment" in body ->
                        throw CloudflareBlockedException(getString(R.string.cloudflare_network_mismatch))

                    else ->
                        throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出現在影片界面，當你v數不大時會報403
                }
            } else throw IllegalStateException("$code ${message()}")

            500 -> throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出現在影片界面，當你v數很大時會報500

            404 -> if (!isAlreadyLogin) {
                throw IllegalStateException(getString(R.string.not_logged_in_currently))
            } else {
                throw IllegalStateException("$code ${message()}")
            }

            else -> throw IllegalStateException("$code ${message()}")
        }
    }

    internal fun handleException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException -> throw e
            is ParseException -> {
                e.printStackTrace()
                ParseException(getString(R.string.parse_error_msg))
            }

            is SSLHandshakeException -> {
                e.printStackTrace()
                SSLHandshakeException(getString(R.string.ssl_handshake_error))
            }

            else -> {
                e.printStackTrace()
                e
            }
        }
    }

    /**
     * nJAV 专用的异常处理。
     *
     * ⚠️ **刻意不复用 [handleException]**：它会把**所有** [ParseException] 的 message
     * 一律替换成 `parse_error_msg`（"可能这个网址解析起来不太一样…"）。那条兜底文案
     * 是给 hanime 那套「开发者风格」的 ParseException 准备的（形如
     * `[Parse::func => var] parse error!`），但 nJAV 抛出的信息本身就是中文、
     * 而且指明了失败在哪一步 —— 替换掉等于**把唯一的线索抹掉**。
     *
     * 真正踩过的坑：`NjavPacker` 的正则在 Android 上编译失败（ICU 与 JVM 的正则
     * 差异），导致那个类被永久标记为「初始化失败」，之后每次引用都抛
     * `NoClassDefFoundError: …logic.njav.NjavPacker`。这条消息**本身已经是全部线索**
     * 了，要是再被通用文案盖掉，就真的无从下手。
     *
     * 所以这里的原则是：**有多少信息就给多少**，并且一定带上异常类名。
     */
    internal fun handleNjavException(e: Throwable): Throwable {
        if (e is CancellationException) throw e
        e.printStackTrace()
        val detail = e.message?.takeIf { it.isNotBlank() }
        return ParseException(
            when {
                // nJAV 自己抛的（"nJAV：…" / "nJAV: HTTP …"），原样保留
                detail != null && detail.startsWith("nJAV") -> detail
                // 其它异常（网络层 / 解析库里冒出来的）：至少让用户和日志看到是哪个类
                detail != null -> "nJAV 加载失败（${e::class.java.simpleName}）：$detail"
                else -> "nJAV 加载失败（${e::class.java.simpleName}）"
            }
        )
    }

    //</editor-fold>

    private fun getString(resId: Int) = applicationContext.getString(resId)
}
