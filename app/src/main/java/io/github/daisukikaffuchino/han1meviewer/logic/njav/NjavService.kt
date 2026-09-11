package io.github.daisukikaffuchino.han1meviewer.logic.njav

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

/**
 * nJAV 的页面接口。
 *
 * nJAV 是纯 SSR 站点（交互由 Alpine.js 负责），列表 / 详情所需的字段
 * 全都在首屏 HTML 里，所以一个「取 URL 返回 HTML」的方法就够了 —— 不需要
 * 为每个栏目单独声明 endpoint，也不必去猜它的内部 API。
 *
 * `Referer` / `Accept-Language` 是必须的：站点会按这两个头决定语种与是否放行。
 */
interface NjavService {

    @Headers(
        "Referer: https://njavtv.com/",
        "Origin: https://njavtv.com",
        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language: zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7",
    )
    @GET
    suspend fun get(@Url url: String): Response<ResponseBody>
}
