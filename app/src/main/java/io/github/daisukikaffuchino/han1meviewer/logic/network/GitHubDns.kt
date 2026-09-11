package io.github.daisukikaffuchino.han1meviewer.logic.network

import okhttp3.Dns
import java.net.InetAddress

/**
 * GitHub 域名的**内置 IP 解析**，专供「检查更新 / 下载更新包」这两条链路使用。
 *
 * ## 为什么需要它
 *
 * 在部分网络下（内地尤其常见），`github.com` / `*.githubusercontent.com` 会被
 * **DNS 投毒**：解析返回的 IP 根本连不上（超时、RST）。这时无论怎么重试、换镜像都没用，
 * 因为请求在「解析」这一步就已经废了。表现就是**检查更新能弹、但安装包永远下不动**。
 *
 * 参考项目 `HanimeViewer999`（`com.yenaly` 原版）之所以「能正常更新」，核心差异就在这里：
 * 它的 `GitHubDns` 在**应用层**把域名钉到已知可用的 IP，绕开被污染的系统解析。
 *
 * ## 和本机 hosts 的关系
 *
 * 如果开了 Watt Toolkit / Steam++ 之类的 GitHub 加速，它会在 hosts 里把
 * `github.com` 等域名指到 `127.0.0.1`（本地反代）。钉真实 IP 会**绕开**这层加速 ——
 * 实测两条路的速度是一样的（本机出口约 30–40 KB/s，`release-assets` 那条一直是瓶颈），
 * 所以没有损失；而换来的是「域名被污染时仍然能连上」这个能力。
 *
 * > ⚠️ 注意 `release-assets.githubusercontent.com` 才是真正的下载域名：
 * > `github.com/.../releases/download/...` 会 302 过去。它**常常不在 hosts 加速名单里**，
 * > 于是回落到真实的慢 IP。这里一并钉住。
 *
 * 不在表里的域名原样交给系统 DNS，不影响其它请求。
 */
object GitHubDns : Dns {

    /**
     * `*.githubusercontent.com` 共用一段 Fastly IP（`185.199.108–111.133`），
     * `release-assets` / `objects` / `raw` 都适用。
     */
    private val githubusercontentIps = listOf(
        "185.199.108.133",
        "185.199.109.133",
        "185.199.110.133",
        "185.199.111.133",
    )

    private val ipsByHost: Map<String, List<String>> = mapOf(
        "github.com" to listOf(
            "20.205.243.166",
            "140.82.121.3",
            "140.82.116.4",
            "140.82.121.4",
        ),
        "api.github.com" to listOf(
            "140.82.116.6",
            "20.205.243.168",
            "140.82.121.6",
        ),
        "codeload.github.com" to listOf(
            "20.205.243.166",
            "140.82.121.3",
        ),
        "release-assets.githubusercontent.com" to githubusercontentIps,
        "objects.githubusercontent.com" to githubusercontentIps,
        "raw.githubusercontent.com" to githubusercontentIps,
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val candidates = ipsByHost[hostname.lowercase()] ?: return Dns.SYSTEM.lookup(hostname)
        val resolved = candidates.mapNotNull { ip ->
            runCatching { InetAddress.getByName(ip) }.getOrNull()
        }
        // 内置表全部解析失败时退回家系统 DNS，至少不把请求彻底堵死
        return resolved.ifEmpty { Dns.SYSTEM.lookup(hostname) }
    }
}
