package io.github.daisukikaffuchino.han1meviewer.logic.njav

import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HCookieJar
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /**
     * nJAV 的简体中文路径前缀。
     *
     * 列表 / 搜索仍在它下面（`/cn/new`、`/cn/search/xxx`），但**详情页已经不在**了 ——
     * 站点改版后详情走裸 slug，见 [detailUrl]。
     */
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

    /**
     * 女优索引页 `/cn/actresses` 的第 n 页。
     *
     * ⚠️ 单数 `/cn/actress` 是 404，站点用的是复数。
     * 站点**不支持按名字检索女优**（`?q=` / `?keyword=` / `?name=` 实测都被忽略，
     * 只有 `?page` / `?sort` / `?height` / `?cup` / `?age` / `?debut` 生效），
     * 所以名字过滤只能由 UI 在已加载的条目上做。
     */
    fun actressIndexUrl(page: Int): String = listUrl(ACTRESSES_SEGMENT, page)

    /**
     * 某位女优的影片列表页 `/cn/actresses/<编码后的名字>` 的第 n 页。
     *
     * 站点会把裸地址 301 到带随机数字前缀的 `/dm###/cn/actresses/…`——**那个前缀会变**
     * （`/dm539/` 自己都能 301 到别处），所以这里只拼裸地址、让 OkHttp 跟跳转，
     * 与 [detailUrl] 是同一套思路。实测裸地址与 `?page=N` 都能正常 200。
     *
     * @param path 形如 `actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3`，
     *   由 [io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress.path] 提供。
     */
    fun actressUrl(path: String, page: Int): String = listUrl(path.trim('/'), page)

    private const val ACTRESSES_SEGMENT = "actresses"

    /**
     * 详情页地址：**裸 slug**，不带 `/cn/` 语言前缀。
     *
     * ⚠️ 2026-09 站点改版，旧的 `/cn/{slug}` 形式已经废了。请求它会吃到 301，
     * 而且落点不是详情页、是「最近更新」列表页：
     *
     * ```
     * GET https://njavtv.com/cn/scop-715
     *   → 301 Location: https://njavtv.com/dm539/cn/new      ← 列表页！
     * ```
     *
     * 于是 [NjavParser.video] 拿到的其实是一张列表页：`og:title` 抠不到、
     * [NjavPacker.extractM3u8] 也抠不到任何 m3u8，最后抛
     * `ParseException("nJAV：未能解析播放地址")`，UI 侧表现为「点进去播不了」
     * 并跳浏览器。**这就是 nJAV 详情页打不开的真凶**。
     *
     * 站内卡片现在给出的是不带语言前缀的规范地址（`https://njavtv.com/scop-715`），
     * 少数还带一层随机数字前缀（`https://njavtv.com/dm75/waaa-214`）——那个前缀
     * 是会变的（`/dm539/` 自己 301 到 `/dm339`），**不能照抄**，所以统一走裸 slug。
     * 实测 `GET https://njavtv.com/<slug>` → 200，且能正常解出 `playlist.m3u8`。
     */
    fun detailUrl(slug: String): String {
        val value = slug.trim()
        // 万一是外面传进来的绝对地址（分享链接 / 历史记录），原样放行。
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return value
        }
        return BASE_URL + value.trimStart('/')
    }

    /** 播放与下载共用的防盗链域名，包含其子域名。 */
    private val PROTECTED_HOSTS = listOf("surrit.com", "fourhoi.com")

    /**
     * nJAV 的视频 CDN 有防盗链：不带 Referer 直接 403（Cloudflare）。
     * 播放器（含 HLS 的每个分片）与**下载 Worker** 都要把这组头带上，
     * 否则会「能解析出地址但播不了 / 一片也下不动」。
     *
     * ⚠️ 别只判 `surrit.com`：`fourhoi.com` 是同一套防盗链下的另一个域名，
     * 判漏了就会在换域名时变成「清一色 403」——而 403 的表现恰好是
     * 「有地址、有分片数，但一个字节都下不来」。
     */
    fun playbackHeadersFor(url: String): Map<String, String> {
        val host = url.toHttpUrlOrNull()?.host ?: return emptyMap()
        return if (PROTECTED_HOSTS.any { host == it || host.endsWith(".$it") }) {
            mapOf("Referer" to REFERER, "Origin" to ORIGIN)
        } else {
            emptyMap()
        }
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
            .proxyAuthenticator(HProxyAuthenticator.http)
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
