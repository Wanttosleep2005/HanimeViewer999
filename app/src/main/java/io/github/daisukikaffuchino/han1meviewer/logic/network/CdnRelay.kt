package io.github.daisukikaffuchino.han1meviewer.logic.network

import android.content.Context
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 被封 CDN 的 TLS 中转（自建，跑在用户自己的美国 VPS 上）。
 *
 * ## 为什么「挂代理」救不了视频
 *
 * 这是本工程最容易误判的一处。`vdownload.hembed.com`（hanime 全部视频与封面）从大陆
 * 不可达，且**换了代理也一样** —— 原因不是代理没生效，而是**代理是明文隧道**：
 *
 * - SOCKS5 / HTTP 代理只搬运 TCP 字节，**不加密**；
 * - TLS 的 SNI 位于 ClientHello 的**明文**部分；
 * - 于是墙在「客户端 → 代理」这一段就能读到里面的 `vdownload.hembed.com`，直接 RST。
 *
 * 实测（2026-09-12，中国移动，经一台**能正常工作**的 SOCKS5 代理）：
 *
 * | 目标 | 结果 | 说明 |
 * |---|---|---|
 * | `https://hanime1.com/` | **200** | SNI 未被封 |
 * | `https://hanime1.me/` | **RST** | SNI 被封 |
 * | `https://vdownload.hembed.com/` | **RST** | SNI 被封 |
 *
 * 所以「详情页能开、视频 0:00 不动」与代理好坏无关，是**协议层**的问题。
 *
 * ## 解法：让中转站终结 TLS
 *
 * 客户端不谈 hembed，改为和**自己的服务器**用 TLS 通信（SNI 为空 IP 字面量，墙不拦），
 * 由服务器再去取视频：
 *
 * ```
 * 手机 ──TLS(SNI: 186.241.94.98)──▶ 自建中转 ──TLS(SNI: vdownload.hembed.com)──▶ hembed
 * ```
 *
 * 服务器在墙外，它的出站请求不受影响；墙这一侧只看得到「手机在和一个普通境外 IP 说 TLS」。
 *
 * ## 证书
 *
 * 中转用**自签证书**（[R.raw.relay_cert]，SAN 里带 `IP:186.241.94.98`，10 年有效）。
 * 自签不等于不安全：证书随 APK 内置，等于**把公钥钉死在应用里**，比系统 CA 更严 ——
 * 别人拿不到私钥就冒充不了这台中转。因此 [trustManager] 是「系统 CA + 内置证书」的组合：
 * 其它域名照旧走系统信任，只有这一个 IP 多认一张证书。
 *
 * ## 与 [interceptor.ImageRelayInterceptor] 的关系
 *
 * 两者都是「直连优先、失败才走中转」，但中转目的地不同：
 * 本类走**自己的服务器**（图片、视频都能过），`wsrv.nl` 只处理图片且会泄露 URL 给第三方。
 * [CdnRelayInterceptor] 装在更外层，正常情况下 `wsrv.nl` 那层根本不会被触发。
 */
object CdnRelay {

    /**
     * 中转端点。写死在 APK 里（用户自建，不打算公开分发），
     * 通过「网络设置 → CDN 中转」开关控制是否启用。
     */
    const val HOST = "186.241.94.98"
    const val PORT = 7443

    /**
     * 路径口令。中转只允许取 [BLOCKED_HOSTS] 里的域名，所以即使这个值泄露，
     * 别人最多也只能拿它当一个「hembed 专用代理」，无法当开放代理滥用。
     */
    private const val SECRET = "tGb5QULX7mDx71kW5wV68p3zhCzYxRRv"

    /** 路径前缀，与服务器 `/r/<secret>/<base64url>` 约定一致。 */
    private const val PATH_ROOT = "r"

    /**
     * 必须走中转的域名。
     *
     * 判据是「已被实测确认从大陆不可达」，不要凭猜测往里加：
     * 多写一个域名，只会在直连本来能通的用户那里白绕一趟境外服务器。
     */
    private val BLOCKED_HOSTS = setOf(
        "hembed.com",   // hanime 全部视频（vdownload.hembed.com）与全部封面图
        "fourhoi.com",  // nJAV 封面
    )

    /**
     * 会话级的「直连必死」记忆。
     *
     * 直连失败一次要花掉一次 RST 的时间（实测 0.8–2.6 s）。播放一个视频会发出
     * 几十上百个 Range 请求，如果每个都先撞一次墙再中转，等于白白多等几分钟。
     * 这里按 host 记一次结论，本进程内后续请求直接走中转。
     *
     * 不落盘：网络环境会变（换 Wi-Fi、开/关代理），下一次启动重新探一次最稳。
     */
    private val directIsKnownDead = ConcurrentHashMap.newKeySet<String>()

    fun isRelayHost(host: String): Boolean {
        val lower = host.lowercase().removeSuffix(".")
        return BLOCKED_HOSTS.any { lower == it || lower.endsWith(".$it") }
    }

    fun isKnownDead(host: String): Boolean = directIsKnownDead.contains(host.lowercase())

    fun markKnownDead(host: String) = directIsKnownDead.add(host.lowercase())

    /** 用户可在设置里关掉（隐私 / 自己的线路本来就能直连时没必要绕）。 */
    val enabled: Boolean
        get() = runCatching { SettingsRepository.allowCdnRelay }.getOrDefault(true)

    /**
     * 把原始被封 URL 包成中转 URL。
     *
     * 用 base64url 承载原始 URL，而不是塞进查询串，有两个原因：
     * 1. 服务器侧只需一次解码，不必再解析嵌套 URL；
     * 2. 原 URL 自带 `?secure=xxx==,123` 这种带 `=` 与 `,` 的签名，
     *    放进查询串极易被各层规范化坏掉，而这段签名是不能动的。
     *
     * base64url 的字母表是 `A-Za-z0-9-_`，不含 `/`，正好是**单个路径段**，
     * 也不会被 OkHttp 二次转义。
     */
    fun relayUrl(original: String): String? {
        // 先确认确实是个合法 URL：不然后面会带一个「永远取不到」的中转请求出去，
        // 报错还会显示成中转的问题，排查时容易被带偏。
        original.toHttpUrlOrNull() ?: return null
        val encoded = android.util.Base64.encodeToString(
            original.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        return "https://$HOST:$PORT/$PATH_ROOT/$SECRET/$encoded".toHttpUrlOrNull()?.toString()
    }

    /**
     * 「系统 CA + 内置中转证书」的组合信任。
     *
     * 为什么能随便加：这张证书的 SAN 只有 `IP:186.241.94.98`，
     * 就算某个攻击者把它拿去别的域名，hostname 校验也过不了；
     * 反过来只要连的是这个 IP，能通过校验的证书就必须持有我们的私钥。
     */
    val trustManager: X509TrustManager by unsafeLazy { buildTrustManager(applicationContext) }

    val sslContext: SSLContext by unsafeLazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    private fun buildTrustManager(context: Context): X509TrustManager {
        val system = defaultTrustManager()
        val relay = runCatching {
            context.resources.openRawResource(R.raw.relay_cert).use { stream ->
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(stream) as X509Certificate
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setCertificateEntry("cdnRelay", certificate)
                }
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(keyStore) }
                    .trustManagers
                    .filterIsInstance<X509TrustManager>()
                    .firstOrNull()
            }
        }.getOrNull()

        if (relay == null) return system
        return CompositeTrustManager(system, relay)
    }

    private fun defaultTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /** 依次询问：任一信任链通过即放行。两者都拒绝才抛。 */
    private class CompositeTrustManager(
        private val first: X509TrustManager,
        private val second: X509TrustManager,
    ) : X509TrustManager {

        private val accepted = first.acceptedIssuers + second.acceptedIssuers

        private fun <T> tryBoth(block: (X509TrustManager) -> T): T {
            val firstError = runCatching { block(first) }
            if (firstError.isSuccess) return firstError.getOrThrow()
            return block(second)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            tryBoth { it.checkClientTrusted(chain, authType) }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            tryBoth { it.checkServerTrusted(chain, authType) }

        override fun getAcceptedIssuers(): Array<X509Certificate> = accepted
    }
}
