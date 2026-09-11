package io.github.daisukikaffuchino.han1meviewer.logic.njav

import io.github.daisukikaffuchino.han1meviewer.logic.network.HCookieJar
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * nJAV（njavtv.com）站点入口。
 *
 * 这里**没有**复用 hanime 的 [io.github.daisukikaffuchino.han1meviewer.logic.network.ServiceCreator.hClient]：
 * 那条链路挂了 hanime 专用的 Cloudflare 挑战处理与 Getchu 相关拦截器，对 nJAV
 * 只会帮倒忙。但用户配置的**代理 / DoH / 自定义 DNS / UA** 仍然要继承，
 * 所以照抄了同样的 `HProxySelector` + `HDns` + [UserAgentInterceptor] 组合。
 *
 * ⚠️ nJAV 与 missav 是同一家（详情页的防盗链脚本里就列着 missav.ws / missav.ai），
 * 视频统一放在 `surrit.com`，**必须带 Referer 才能拉 m3u8**，见 [playbackHeadersFor]。
 */
object NjavNetwork {

    const val BASE_URL = "https://njavtv.com/"

    /** nJAV 的简体中文路径前缀，列表 / 搜索 / 详情都在它下面。 */
    const val LOCALE = "cn"

    const val ORIGIN = "https://njavtv.com"
    const val REFERER = "https://njavtv.com/"

    val homeUrl: String get() = BASE_URL + LOCALE

    fun listUrl(path: String): String = "$BASE_URL$LOCALE/" + path.trim('/')

    fun searchUrl(keyword: String, page: Int): String {
        val base = "$BASE_URL$LOCALE/search/${URLEncoder.encode(keyword, "UTF-8")}"
        return if (page <= 1) base else "$base?page=$page"
    }

    /** 分类页的第 n 页（第 1 页不带参数）。 */
    fun listUrl(path: String, page: Int): String {
        val base = listUrl(path)
        return if (page <= 1) base else "$base?page=$page"
    }

    fun detailUrl(slug: String): String = "$BASE_URL$LOCALE/$slug"

    /**
     * surrit.com 有防盗链：不带 Referer 直接 403（Cloudflare）。播放器要把
     * 这组头透传给播放引擎（含 HLS 的分片请求），否则会「能解析出地址但播不了」。
     */
    fun playbackHeadersFor(url: String): Map<String, String> =
        if (url.contains("surrit.com", ignoreCase = true)) {
            mapOf("Referer" to REFERER, "Origin" to ORIGIN)
        } else {
            emptyMap()
        }

    private val dns = HDns()

    private val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .cookieJar(HCookieJar())
            .proxySelector(HProxySelector())
            .dns(dns)
            .build()
    }

    val service: NjavService by unsafeLazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .build()
            .create(NjavService::class.java)
    }
}
