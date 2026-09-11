package io.github.daisukikaffuchino.han1meviewer

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.char
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource

/**
 * 我觉得空字符串写出来太逆天了，所以搞了个常量
 */
const val EMPTY_STRING = ""

const val APP_NAME = "Han1meViewer"

// 标准时间格式

/* yyyy-MM-dd */
@JvmField
val LOCAL_DATE_FORMAT = LocalDate.Formats.ISO

/* yyyy-MM-dd HH:mm */
@JvmField
val LOCAL_DATE_TIME_FORMAT = LocalDateTime.Format {
    date(LocalDate.Formats.ISO); char(' ')
    hour(); char(':'); minute()
}

// 网络基本设置

const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

// 設置發佈日期年份，在搜索的tag裏

/**
 * 發佈日期年份開始於
 */
const val SEARCH_YEAR_RANGE_START = 1990

/**
 * 發佈日期年份結束於
 */
const val SEARCH_YEAR_RANGE_END = BuildConfig.SEARCH_YEAR_RANGE_END

const val VIDEO_COMMENT_PREFIX = "video"

const val PREVIEW_COMMENT_PREFIX = "preview"

// base url

val HANIME_BASE_URL: String
    get() = SettingsRepository.baseUrl

/**
 * 如果添加备选网址别忘了确认[String.toVideoCode]的videoUrlRegex
 */
object HanimeConstants {
    val HANIME_HOSTNAME = arrayOf("hanime1.me","hanime1.com","hanimeone.me","javchu.com")
    val HANIME_URL = arrayOf("https://hanime1.me/","https://hanime1.com/","https://hanimeone.me/","https://javchu.com/")
    val ANIME_URL = arrayOf("https://hanime1.me/","https://hanime1.com/","https://hanimeone.me/")

    /**
     * nJAV（njavtv.com）—— 独立数据源，只有这一个域名。
     *
     * 它**不属于** [HANIME_URL] / [ANIME_URL] 这两组 hanime 镜像：走的是另一套
     * 网络层与解析器（[io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork]）。
     * 之所以在「域名」列表里也给它留一项，是为了让「数据源」与「域名」在 UI 上
     * 始终指向同一个站点 —— 选了 nJAV 数据源，域名就必须是 njavtv.com，
     * 否则抽屉头部会显示成 hanime 的地址。
     */
    const val NJAV_HOSTNAME = "njavtv.com"
    const val NJAV_URL = "https://njavtv.com/"

    /**
     * 站点的显示名 → 数据源。
     *
     * njavtv.com 走独立数据源，其余（含 hanime 各镜像与 javchu）都归
     * [io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource.Hanime1]。
     */
    fun siteSourceOf(url: String): SiteSource =
        if (url.contains(NJAV_HOSTNAME, ignoreCase = true)) SiteSource.Njav
        else SiteSource.Hanime1
}

val HANIME_LOGIN_URL: String
    get() = HANIME_BASE_URL + "login"

// github url

/**
 * 上游原作者的仓库（只做署名引用，不要再作为「项目仓库」指向）。
 */
const val UPSTREAM_GITHUB_URL = "https://github.com/daisukiKaffuChino/Han1meViewer"

/**
 * 当前这个 fork（mod 线）的仓库地址 —— 「关于 → 项目仓库」「提交 bug」「论坛」都指这里。
 *
 * ⚠️ 这个 URL 是**硬编码进 APK** 的，换仓库必须重新打包发版，旧包改不掉。
 */
const val HA1_GITHUB_URL = "https://github.com/ddsmie4t2g/HanimeViewer"

const val HA1_GITHUB_ISSUE_URL = "$HA1_GITHUB_URL/issues"

const val HA1_GITHUB_FORUM_URL = "$HA1_GITHUB_URL/discussions"

/** 「关于 → 开发者」里显示的二次开发者 GitHub 用户名。 */
const val SECONDARY_DEVELOPER_HANDLE = "ddsmie4t2g"

/** 点击「关于 → 开发者」跳转的个人主页。 */
const val SECONDARY_DEVELOPER_GITHUB_URL = "https://github.com/$SECONDARY_DEVELOPER_HANDLE"
// for Shared Preference

const val LOGIN_COOKIE = "cookie"
const val SAVED_USER_ID = "saved_user_id"

const val CLOUDFLARE_COOKIE = "cf_cookie"
const val CLOUDFLARE_COOKIE_HOST = "cf_cookie_host"

const val ALREADY_LOGIN = "already_login"

// Notification

const val DOWNLOAD_NOTIFICATION_CHANNEL = "download_channel"

const val UPDATE_NOTIFICATION_CHANNEL = "update_channel"

// File

const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileProvider"
const val GETCHU_BASE_URL = "https://www.getchu.com/"

// Search

/**
 * 站内搜索「分类」筛选里「里番」对应的 search_key（见 assets/search_options/genre.json）。
 * 预告页的月度归档检索必须带上它，否则会把 3D动画 / MMD / Cosplay / AI生成 等
 * 其它分类一起塞进「里番新番列表」。
 */
const val HANIME_GENRE_ANIME = "裏番"
