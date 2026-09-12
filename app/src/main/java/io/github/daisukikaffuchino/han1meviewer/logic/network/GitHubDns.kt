package io.github.daisukikaffuchino.han1meviewer.logic.network

import okhttp3.Dns
import java.net.InetAddress

/**
 * 更新链路（GitHub + jsDelivr）域名的**内置 IP 解析**，专供「检查更新 / 下载更新包」使用。
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
        // jsDelivr 是「检查更新」的首选源（GitHub 内容加速）。
        //
        // ⚠️ **这组 IP 曾经钉错过，而且代价很隐蔽**：早期钉的是 Cloudflare 段的
        // `104.17.208.5` / `104.17.207.5`，到 2026-09-12 实测**两个都连不上**
        // （connect 直接超时），而 jsDelivr 早已换到 Fastly 段 —— 系统 DNS 解析
        // `cdn.jsdelivr.net` 得到的是 `151.101.x.x`，访问正常。
        //
        // 后果不是「检查更新失败」，而是**每次都先白等一个 connectTimeout（15 s）**
        // 才回退到 raw 那条源。因为 [lookup] 一旦返回内置 IP，OkHttp 就不会再问系统 DNS，
        // 内置 IP 全不通 = 这个域名在这台设备上彻底不可用。
        //
        // 下面这组是 Fastly 为 jsDelivr 提供服务的共享 anycast 段（实测可连）。
        // 但仍然可能再过期 —— 真正的保护是 [lookup] 里的系统 DNS 兜底尾巴。
        "cdn.jsdelivr.net" to listOf(
            "151.101.1.229",
            "151.101.65.229",
            "151.101.129.229",
            "151.101.193.229",
        ),
    )

    /**
     * 解析域名。
     *
     * ⚠️ **内置 IP 后面一定要接上系统 DNS 的结果**（去重后追加），不能只返回内置表。
     *
     * 原因是 OkHttp 只看 [Dns.lookup] 的返回值：内置 IP 一旦过期，OkHttp **不会**
     * 回头去问系统 DNS，于是「钉 IP」这个保护措施会反噬成「这个域名永远连不上」。
     * 上面那个 jsDelivr 的坑就是这么来的 —— 而且是静默的，只在每次检查更新时
     * 多花 15 s，看起来就像「检查更新很慢/有问题」。
     *
     * 追加在**尾部**：正常路径仍然走内置 IP（这才是抗投毒的意义），
     * 只有当它们全部连不上时，OkHttp 的 RouteSelector 才会继续尝试系统解析的地址。
     * 代价是「多绕一次」，而不是「彻底不可用」。
     */
    override fun lookup(hostname: String): List<InetAddress> {
        val candidates = ipsByHost[hostname.lowercase()] ?: return Dns.SYSTEM.lookup(hostname)
        val pinned = candidates.mapNotNull { ip ->
            runCatching { InetAddress.getByName(ip) }.getOrNull()
        }
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }
            .getOrDefault(emptyList())

        val merged = (pinned + system).distinctBy { it.hostAddress }
        // 内置表全部解析失败、系统 DNS 也拿不到时，好歹别把请求彻底堵死
        return merged.ifEmpty { runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList()) }
    }
}
