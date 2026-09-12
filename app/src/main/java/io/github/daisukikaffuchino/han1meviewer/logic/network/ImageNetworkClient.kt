package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.CdnRelayInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.ImageRelayInterceptor
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 图片专用网络栈（封面 / 缩略图 / 图标）。
 *
 * 存在的意义是**让两个 Coil 版本共用同一份配置** —— 这个工程同时引了
 * Coil 2（`io.coil-kt:coil`，[io.github.daisukikaffuchino.han1meviewer.util.HImageMeower]）
 * 和 Coil 3（`io.coil-kt.coil3`，各处的 `AsyncImage`），如果各配一份，
 * 很容易出现「首页封面通了、下载列表封面不通」这类一半好一半坏的问题。
 *
 * 配置要点：
 * - [HProxySelector] / [HProxyAuthenticator]：漏挂代理的表现是「文字能加载、图一张不出」，
 *   用户完全看不出是代理没生效（这条经验来自 [io.github.daisukikaffuchino.han1meviewer.util.HImageMeower]）。
 * - [HDns]：系统 DNS 对本站系域名是**投毒**的，图片也得走同一套解析。
 * - [CdnRelayInterceptor]：`hembed` / `fourhoi` 图片走**自建** TLS 中转（自己的服务器，
 *   URL 不外泄给第三方）。正常情况下轮不到下面那层。
 * - [ImageRelayInterceptor]：上一层的**兜底** —— 只有自建中转也拿不到时才退到 `wsrv.nl`。
 *   保留它是因为它不依赖任何自有设施，自建中转哪天挂了封面还不至于全黑。
 */
object ImageNetworkClient {

    val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(CdnRelay.sslContext.socketFactory, CdnRelay.trustManager)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(HDns())
            .addInterceptor(CdnRelayInterceptor())
            .addInterceptor(ImageRelayInterceptor())
            .build()
    }
}
