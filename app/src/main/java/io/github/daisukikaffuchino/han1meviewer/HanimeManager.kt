package io.github.daisukikaffuchino.han1meviewer

import android.webkit.CookieManager
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import androidx.core.text.parseAsHtml
import io.github.daisukikaffuchino.han1meviewer.logic.network.HCookieJar
import io.github.daisukikaffuchino.han1meviewer.util.CookieString
import kotlinx.serialization.json.Json

@JvmField
val HJson = Json {
    ignoreUnknownKeys = true
}

/**
 * 给用户显示的错误信息
 *
 * ぴえん化
 */
val Throwable.pienization: CharSequence get() = "🥺\n$localizedMessage"

// base

/**
 * 獲取 Hanime 影片地址
 *
 * ⚠️ nJAV 数据源下 `videoCode` 是番号 slug（如 `venx-381`），对应
 * `https://njavtv.com/cn/{slug}`，**不是** hanime 的 `watch?v=` 形式。
 */
fun getHanimeVideoLink(videoCode: String) =
    if (SettingsRepository.isNjavSite) NjavNetwork.detailUrl(videoCode)
    else HANIME_BASE_URL + "watch?v=" + videoCode


/**
 * 獲取 Hanime 搜索地址
 */
fun getHanimeSearchLink(artist: String) =
    if (SettingsRepository.isNjavSite) NjavNetwork.searchUrl(artist, 1)
    else HANIME_BASE_URL + "search?query=" + artist
/**
 * 獲取 Hanime 影片分享文本
 */
fun getHanimeShareText(title: String, videoCode: String): String = buildString {
    appendLine(title)
    appendLine(getHanimeVideoLink(videoCode))
    append("- From Han1meViewer -")
}
/**
 * 獲取 Hanime 影片分享文本
 */
fun getHanimeSearchShareText(artist: String): String = buildString {
    appendLine(artist)
    appendLine(getHanimeSearchLink(artist))
    append("- From Han1meViewer -")
}

/**
 * 獲取 Hanime 影片**官方**下載地址
 *
 * nJAV 没有官方下载页，退化成详情页链接。
 */
fun getHanimeVideoDownloadLink(videoCode: String) =
    if (SettingsRepository.isNjavSite) NjavNetwork.detailUrl(videoCode)
    else HANIME_BASE_URL + "download?v=" + videoCode

val videoUrlRegex = Regex(
    """(?:(?:https?:)?//[^\s"'<>/]+|hanime(?:1|one)\.(?:com|me))?(?:/[^/?#\s"'<>]+)*/watch\?(?:[^#\s"'<>]*&)?v=(\d+)"""
)

fun String.toVideoCode() = videoUrlRegex.find(this)?.groupValues?.get(1)

// log in and log out

suspend fun logout() {
    SettingsRepository.update { it.copy(isAlreadyLogin = false, loginCookie = EMPTY_STRING, savedUserId = EMPTY_STRING) }
    HCookieJar.cookieMap.clear()
    CookieManager.getInstance().removeAllCookies(null)
}

suspend fun login(cookies: String) =
    SettingsRepository.update { it.copy(isAlreadyLogin = true, loginCookie = cookies) }

suspend fun login(cookies: List<String>) {
    login(cookies.joinToString(";") {
        it.substringBefore(';')
    })
}
