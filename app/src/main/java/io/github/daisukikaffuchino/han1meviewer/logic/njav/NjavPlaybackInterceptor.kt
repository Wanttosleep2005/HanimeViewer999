package io.github.daisukikaffuchino.han1meviewer.logic.njav

import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 给 nJAV 的播放与下载链路注入防盗链头。
 *
 * 视频源 `surrit.com` 的 **m3u8 与每一个分片**都必须带 `Referer`，
 * 否则 Cloudflare 直接回 403（实测：不带 → `403` + HTML 错误页；带上 → `200`）。
 *
 * 理论上播放器层的 `setDefaultRequestProperties` 已经能做这件事，但这条链路反复出问题，
 * 所以在 **OkHttp 层面再兜一道**：只要是该 CDN 的请求、且调用方没显式指定 `Referer`，
 * 就补上。成本几乎为零，但能彻底排除「头没带上 → 403 → 视频 0:00 / 0」这一类故障。
 *
 * 作为 network interceptor 安装，让重定向后的 CDN 请求也能补齐请求头。
 */
class NjavPlaybackInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = NjavNetwork.playbackHeadersFor(request.url.toString())
        if (headers.isEmpty()) return chain.proceed(request)

        val builder = request.newBuilder()
        headers.forEach { (name, value) ->
            if (request.header(name).isNullOrBlank()) builder.header(name, value)
        }
        return chain.proceed(builder.build())
    }
}

/**
 * 播放链路专用的 [OkHttpClient]。
 *
 * ## 为什么播放器不能直接用 [androidx.media3.datasource.DefaultHttpDataSource]
 *
 * `DefaultHttpDataSource` 内部走 `HttpURLConnection`，也就是**绕开了应用自己的网络栈**：
 *
 * 1. **DNS** —— 用系统解析，拿不到 [HDns] 的内置 IP 兜底。
 *    `njavtv.com` 在国内被投毒、`surrit.com` 也在兜底表里，
 *    播放器走系统 DNS 就等于把这些兜底全丢了。
 * 2. **代理** —— 用户在设置里配的代理由 [HProxySelector] 提供，
 *    但 `HttpURLConnection` 只认系统 `ProxySelector`。用户若靠代理才能出网，
 *    症状就正好是「**详情页能打开（走 OkHttp），一播放就 0:00 / 0（走 ExoPlayer）**」。
 * 3. **超时** —— `DefaultHttpDataSource` 默认 connect/read 各 8s，
 *    surrit 在 Cloudflare 上，弱网下极容易触顶。
 *
 * 换成 [androidx.media3.datasource.okhttp.OkHttpDataSource] 后，HLS 的
 * **主列表、子列表、每个分片**都复用同一个 client，上面三点一并解决；
 * 连接池还能在 3600+ 个分片之间复用，比每片新建连接快得多。
 *
 * ## 超时取值
 *
 * `readTimeout` 是「**两次数据到达之间的最大间隔**」，不是总时长。
 * 分片只有几百 KB，30s 足够宽松。**绝不能**用 `callTimeout`：
 * 那是整条请求的总时长上限，设了会把长视频流从中间掐断，故显式置 0。
 */
object PlaybackHttpClient {

    val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // 0 = 不限制总时长（视频是长连接，设了就必然被掐）
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .dns(HDns())
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .addNetworkInterceptor(NjavPlaybackInterceptor())
            .build()
    }
}
