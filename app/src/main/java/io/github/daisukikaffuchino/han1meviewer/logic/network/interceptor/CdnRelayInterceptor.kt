package io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor

import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
import io.github.daisukikaffuchino.utils.LogUtil
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 把被封 CDN（`vdownload.hembed.com` / `fourhoi.com`）的请求改道到自建 TLS 中转。
 *
 * 背景与原理见 [CdnRelay] 的类注释 —— 一句话：这两个域名在内地**连代理都救不了**，
 * 因为 TLS 的 SNI 是明文，墙在明文隧道里就能读到并 RST，只有「中转站终结 TLS」这一条路。
 *
 * ## 为什么是「直连优先，失败才中转」
 *
 * | 用户位置 | 直连 | 结果 |
 * |---|---|---|
 * | 海外 / 港澳台 | 通 | 完全不碰中转，最快也最省流量 |
 * | 内地 | 立即 RST | 失败一次（0.8–2.6 s），随后本进程内记住结论 |
 *
 * 判定**只认异常（[IOException]）**，不认 4xx/5xx：`hembed` 在签名过期时会返回 403，
 * 那是「链接坏了」而不是「线路被墙了」，这种请求送去中转也一样是 403，
 * 并会平白把「直连线路已死」的错误结论写进缓存。
 *
 * 记忆是会话级的（见 [CdnRelay.isKnownDead]）：一个视频播放会发几十上百个 Range 请求，
 * 每次都先撞一次墙的话，白等的时间比下载本身还长。
 *
 * ## 只改 URL，不动其它
 *
 * 请求头（`Range`、`User-Agent`，以及 nJAV 那条链路的 `Referer`）原样带过去 ——
 * 视频的 `Range` 是播放器跳转/拖动的基础，少一个字节都可能表现为「拖不动」。
 */
class CdnRelayInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host.lowercase()
        if (!CdnRelay.isRelayHost(host)) return chain.proceed(request)
        // 用户可在「网络设置 → CDN 中转」关掉。每次请求都读一次，改设置立即生效。
        if (!CdnRelay.enabled) return chain.proceed(request)

        if (CdnRelay.isKnownDead(host)) {
            // 已验证过直连必死。中转若也被探活判死，就别再绕这一趟了 ——
            // 直接把**直连的原始错误**抛出去：报错指向真实原因，还省掉一次白等的超时。
            if (CdnRelay.cachedReachable == false) return chain.proceed(request)
            return relay(chain, request, null)
        }

        val direct = runCatching { chain.proceed(request) }
        direct.getOrNull()?.let { if (it.isSuccessful) return it }

        // 走到这里只可能是「抛异常」。4xx/5xx 的场景在上面就已经返回了。
        direct.getOrNull()?.close()
        val cause = direct.exceptionOrNull()
        if (cause == null) return chain.proceed(request)

        CdnRelay.markKnownDead(host)
        LogUtil.w(TAG, "直连失败，改走中转: ${request.url} (${cause.message})")
        return relay(chain, request, cause)
    }

    /**
     * 用中转地址重放同一请求。
     *
     * [cause] 非空表示这次重放是「直连失败后的兜底」，中转再失败时**抛直连的错** ——
     * 那才是根因，中转失败通常只是次生现象。
     */
    private fun relay(chain: Interceptor.Chain, request: Request, cause: Throwable?): Response {
        val forwarded = CdnRelay.relayUrl(request.url.toString())
            ?: return cause?.let { throw it } ?: chain.proceed(request)

        return try {
            chain.proceed(request.newBuilder().url(forwarded).build())
        } catch (e: IOException) {
            // 中转自己也可能失败。这里**只安排一次后台复探，不立刻把中转判死** ——
            // 一次网络抖动就把中转停用 5 分钟，代价（视频看不了）远大于收益。
            // 真死了的话复探会记下来，下一个请求就不再白绕。
            CdnRelay.scheduleReprobe()
            // 抛直连的错更能说明问题（中转失败通常是次生现象）。
            cause?.let { throw it }
            throw e
        }
    }

    private companion object {
        const val TAG = "CdnRelay"
    }
}
