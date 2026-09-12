package io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor

import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.utils.LogUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 封面图「直连优先，失败才走中转」的兜底拦截器。
 *
 * ## 为什么需要它
 *
 * 本站两个站族的封面图**都放在被封的域名上**，实测（2026-09-12，中国大陆线路）：
 *
 * | 图源 | 用在哪 | 直连 | 说明 |
 * |---|---|---|---|
 * | `vdownload.hembed.com` | hanime **全部**图片 | **000** | 抓下 hanime1.com 首页 218KB HTML，**155 张图全部**来自这里（含每个封面、图标） |
 * | `fourhoi.com` | nJAV 封面 | **000** | 同页 112 处引用 |
 *
 * 这两个域名的封法是**不通用的**，也解释了为什么换 IP / 换 DNS 都没用：
 * - `hembed.com` 是 **IP 级封 443**：TCP 能连上，但**换任何 SNI（含 `example.com`）都 TLS 失败**，
 *   所以既不是 SNI 过滤、也无法靠域名前置绕过。
 * - `fourhoi.com` 是 **SNI 阻断**：同一个 IP 上 SNI 写 `fourhoi.com` 失败、写 `example.com` 成功。
 *
 * 结论：**纯客户端改 DNS / 改 IP 都救不了封面图**，必须借一跳。这里借的是
 * [RELAY_HOST]（开源图片中转 wsrv.nl）。实测经它中转后：
 * `hembed` 封面 `200` + 46570 字节 + JPEG 头 `FF D8 FF DB`；`fourhoi` 封面 `200` + 69765 字节。
 *
 * ## 为什么是「失败才中转」而不是「一律中转」
 *
 * 走第三方意味着**每个封面的完整 URL（含域名与视频编号）都会经过对方服务器**。
 * 对线路正常的用户来说这是白送的隐私代价，所以：
 *
 * 1. **先直连**：能通就通，全程不碰第三方；
 * 2. 只有直连抛异常（TLS RST / 连不上）**或**返回 4xx/5xx 时，才用同一个 URL 走中转重试一次。
 *
 * ## 只在图片上生效
 *
 * [RELAY_HOST] 的 `wsrv.nl` 只处理图片（实测传 mp4 过去是 `404 application/json`），
 * 所以本拦截器**只对图片扩展名生效**，视频链路不受影响、也不会被误送第三方。
 * 视频（`hembed` 的 mp4）需要的是真正的中转/VPN，不在本拦截器职责内。
 *
 * 装成 application interceptor：直连与重试都在同一层，重定向由 OkHttp 自己跟。
 */
class ImageRelayInterceptor : Interceptor {

    companion object {
        private const val TAG = "ImageRelay"

        /** 中转服务（开源，wsrv.nl / images.weserv.nl）。 */
        private const val RELAY_HOST = "wsrv.nl"

        /**
         * 需要兜底的**被封**图片域名。
         *
         * 只列实测确实不通的，别把正常图源塞进来 —— 那会让本来能直连的图也白白绕一趟第三方。
         */
        private val BLOCKED_IMAGE_HOSTS = setOf(
            "vdownload.hembed.com", // hanime 全站图片
            "fourhoi.com",          // nJAV 封面（含 www 子域，见 matches）
        )

        /** 只中转图片；wsrv.nl 对视频返回 404，传过去没有意义。 */
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "svg", "ico",
        )

        private fun isBlockedImageUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            // 中转自己不要再被中转一次（重试请求会走到这里）。
            if (parsed.host.equals(RELAY_HOST, ignoreCase = true)) return false
            val host = parsed.host.lowercase()
            val matched = BLOCKED_IMAGE_HOSTS.any { host == it || host.endsWith(".$it") }
            if (!matched) return false

            val lastSegment = parsed.pathSegments.lastOrNull().orEmpty()
            val ext = lastSegment.substringAfterLast('.', "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }

        /**
         * 把原图 URL 包成中转 URL。
         *
         * 用 [okhttp3.HttpUrl.Builder.addQueryParameter] 让 OkHttp 自己做百分号编码，
         * 不要手拼字符串：原 URL 自带查询串（`?secure=xxx==,123`），手拼容易把
         * `==` / `,` 弄坏，而这段签名是不能改的。
         *
         * 编码后 `https://` 会变成 `https%3A%2F%2F...`，实测 wsrv.nl 照样能取到图
         * （「全转义 / 只转义冒号斜杠 / 完全不转义」三种写法都返回 200 + 46570 字节），
         * 所以放心交给 OkHttp 规范化。
         */
        private fun relayUrl(original: String): String? =
            "https://$RELAY_HOST/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("url", original)
                ?.build()
                ?.toString()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isBlockedImageUrl(request.url.toString())) return chain.proceed(request)
        // 用户可在「网络设置」里关掉中转（隐私考虑）。每次请求都读一次，
        // 所以改设置立即生效，不需要重启 App。
        // SettingsRepository 在 Application.onCreate 里安装；这里仍兜一层
        // runCatching，避免极端时序下 lateinit 未初始化直接把图片请求打挂。
        val allowed = runCatching { SettingsRepository.allowImageRelay }.getOrDefault(true)
        if (!allowed) return chain.proceed(request)

        val direct = runCatching { chain.proceed(request) }
        // 直连成功且状态码正常：直接用，不碰第三方。
        direct.getOrNull()?.let { if (it.isSuccessful) return it }

        // 走到这里说明直连失败（异常）或返回了 4xx/5xx。
        // 失败的响应体要先关掉，否则连接泄漏。
        direct.getOrNull()?.close()
        val cause = direct.exceptionOrNull()
        LogUtil.w(
            TAG,
            "直连失败，改用中转: ${request.url} (${cause?.message ?: "HTTP error"})",
        )

        val forwarded = relayUrl(request.url.toString())
            ?: return cause?.let { throw it } ?: chain.proceed(request)

        return try {
            chain.proceed(request.newBuilder().url(forwarded).build())
        } catch (e: IOException) {
            // 中转也失败：把「直连的错」抛出去更能说明问题（中转失败通常是次生现象）。
            if (cause != null) throw cause else throw e
        }
    }
}
