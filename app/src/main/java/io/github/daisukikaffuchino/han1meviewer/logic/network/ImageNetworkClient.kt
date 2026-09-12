package io.github.daisukikaffuchino.han1meviewer.logic.network

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
 * - [ImageRelayInterceptor]：封面源（`hembed` / `fourhoi`）被封，**直连优先、失败才走中转**。
 */
object ImageNetworkClient {

    val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(HDns())
            .addInterceptor(ImageRelayInterceptor())
            .build()
    }
}
