package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/10 010 17:01
 */
class HDns : Dns {

    private data class DohRuntimeConfig(
        val url: String,
        val bootstrapIps: List<String>,
        val timeoutSeconds: Int,
    )

    @Volatile
    private var cachedDohConfig: DohRuntimeConfig? = null

    @Volatile
    private var cachedDohDns: Dns? = null

    companion object {

        private val cloudFlareIps = listOf(
            "172.64.229.154", "162.159.0.1", "108.162.192.1", "172.64.33.1", "104.19.0.1",
            "2606:4700:3035::ac43:bb8d", "2606:4700:3030::6815:746", "2606:4700:3030::6815:714"
        )

        private val getchuIps = listOf("210.155.150.166", "210.155.150.145")

        private const val GETCHU_HOSTNAME = "www.getchu.com"

        /**
         * nJAV 系域名的**内置 IP 兜底**。
         *
         * ⚠️ 这里必须无条件生效（不看 `useBuiltInHosts`，也**不给 DoH / 系统 DNS 任何机会**），
         * 原因是 `njavtv.com` 在国内被 **DNS 投毒** —— 实测 2026-09-11：
         *
         * | 解析途径 | 返回 | 实测 |
         * |---|---|---|
         * | 系统 DNS | `31.13.91.33`（**Facebook 的 IP 段**） | 连接超时 |
         * | 阿里 DoH（dns.alidns.com） | `199.96.63.53`（同样是污染结果） | 连接超时 |
         * | 腾讯 DoH（doh.pub） | `104.26.7.251` / `104.26.6.251` / `172.67.70.97` | **200，211 KB 真页面** |
         *
         * 也就是说：**只要走系统 DNS 或阿里 DoH，nJAV 详情页必然打不开**
         * （表现为「点进去加载不出来」，用户容易误判成「反爬」或「播放器不兼容」）。
         * 这几个 IP 是 Cloudflare 的地址，靠 SNI 路由，直接钉住即可。
         *
         * > 站点若换 IP，需要更新这张表。候选来源：`doh.pub` 的 `njavtv.com` A 记录。
         * > 已排除 `199.96.63.53`（阿里 DoH 返回，实测超时，是污染结果）。
         */
        private val javIpsByHost: Map<String, List<String>> = mapOf(
            "njavtv.com" to listOf("104.26.7.251", "104.26.6.251", "172.67.70.97"),
            // 视频源（surrit.com）：系统解析本来是对的（Cloudflare 真实 IP），内置一份是为了
            // 「哪天被投毒了也不至于能解析地址却播不了」。
            // ⚠️ 2026-09-12 复核：doh.pub 给的是 `104.18.53.139` + `104.18.49.25`，
            // 其中 `104.18.49.25` **已经连不上了**（connect 超时），所以只保留可用的那个；
            // 万一它也失效，[lookupBuiltInJav] 的系统 DNS 尾巴会接手。
            "surrit.com" to listOf("104.18.53.139"),
        )

        /**
         * 添加DNS
         */
        private operator fun MutableMap<String, List<InetAddress>>.set(
            host: String, ips: List<String>,
        ) {
            this[host] = ips.map {
                InetAddress.getByAddress(host, InetAddress.getByName(it).address)
            }
        }

        /**
         * 解析自定义 IP 列表，逗号分隔
         */
        fun parseCustomIps(raw: String): List<String> {
            return raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        /**
         * 验证自定义 IP 列表格式是否有效
         * @return 无效 IP 的错误信息列表，为空表示全部有效
         */
        fun validateCustomHosts(raw: String): List<String> {
            val errors = mutableListOf<String>()
            if (raw.isBlank()) return errors
            val ips = parseCustomIps(raw)
            if (ips.isEmpty()) {
                errors.add("No IP addresses entered")
                return errors
            }
            ips.forEach { ip ->
                if (!isValidIpAddress(ip)) {
                    errors.add("Invalid IP address: \"$ip\"")
                }
            }
            return errors
        }

        private fun isValidIpAddress(ip: String): Boolean {
            return runCatching {
                val addr = InetAddress.getByName(ip)
                addr.hostAddress == ip || addr.hostAddress == ip.removePrefix("[")
                    .removeSuffix("]")
            }.getOrDefault(false)
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname == GETCHU_HOSTNAME) {
            return getchuIps.map {
                InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
            }
        }

        // nJAV 系：DNS 已被投毒，无条件走内置 IP（理由见 javIpsByHost 的注释）。
        lookupBuiltInJav(hostname)?.let { return it }

        if (SettingsRepository.useBuiltInHosts && HANIME_HOSTNAME.contains(hostname)) {
            val customIps = resolveCustomIps()
            if (!customIps.isNullOrEmpty()) {
                return customIps.map {
                    InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
                }
            }
            return cloudFlareIps.map {
                InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
            }
        }

        val dohUrl = DohConfig.resolveUrl()
        if (!dohUrl.isNullOrBlank()) {
            return runCatching { lookupByDoH(dohUrl, hostname) }
                .getOrElse {
                    LogUtil.w("DOH", "lookup failed for $hostname: ${it.message}")
                    Dns.SYSTEM.lookup(hostname)
                }
        }

        return Dns.SYSTEM.lookup(hostname)
    }

    /**
     * nJAV 系域名的内置 IP 解析；域名不在表里就返回 null（交回上层走常规流程）。
     *
     * ⚠️ **内置 IP 后面要接上系统 DNS 的结果**。内置表是「防投毒」用的，但它同时
     * 切断了「表过期时的退路」：OkHttp 只看本函数返回的地址，内置 IP 全连不上不会
     * 自动改问系统 DNS。`surrit.com` 就已经出现过一半 IP 失效（见 [javIpsByHost]）。
     * 追加在尾部，正常路径仍然只走内置 IP，代价是「多绕一次」而不是「彻底不通」。
     */
    private fun lookupBuiltInJav(hostname: String): List<InetAddress>? {
        val ips = javIpsByHost[hostname.lowercase()] ?: return null
        val pinned = ips.mapNotNull { ip ->
            runCatching {
                InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address)
            }.getOrNull()
        }
        // njavtv.com 的系统 DNS 是被投毒的，所以系统结果只能放尾巴上，
        // 绝不能排在前面 —— 排前面就等于把投毒结果当首选。
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        return (pinned + system).distinctBy { it.hostAddress }.takeIf { it.isNotEmpty() }
    }

    private fun lookupByDoH(dohUrl: String, hostname: String): List<InetAddress> {
        val config = DohRuntimeConfig(
            url = dohUrl,
            bootstrapIps = DohConfig.bootstrapIps(),
            timeoutSeconds = DohConfig.timeoutSeconds(),
        )
        val dns = getOrCreateDohDns(config)
        return dns.lookup(hostname).also {
            LogUtil.i("DOH", it.toString())
        }
    }

    fun lookupByDoHOnly(hostname: String): List<InetAddress> {
        val dohUrl = DohConfig.resolveUrl() ?: error("DoH is disabled")
        return lookupByDoH(dohUrl, hostname)
    }

    private fun getOrCreateDohDns(config: DohRuntimeConfig): Dns {
        val currentDns = cachedDohDns
        if (currentDns != null && cachedDohConfig == config) return currentDns

        synchronized(this) {
            val dnsAgain = cachedDohDns
            if (dnsAgain != null && cachedDohConfig == config) return dnsAgain

            val client = OkHttpClient.Builder()
                .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()
            val bootstrapHosts = config.bootstrapIps.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            val dnsBuilder = DnsOverHttps.Builder()
                .client(client)
                .url(config.url.toHttpUrl())
                .includeIPv6(true)
                .post(false)
                .resolvePrivateAddresses(true)
                .resolvePublicAddresses(true)
            if (bootstrapHosts.isNotEmpty()) {
                dnsBuilder.bootstrapDnsHosts(bootstrapHosts)
            }
            val dns = dnsBuilder.build()

            cachedDohConfig = config
            cachedDohDns = dns
            return dns
        }
    }

    fun getCDNList(host: String): List<String> {
        if (host == GETCHU_HOSTNAME) {
            return getchuIps.distinct()
        }

        // nJAV 系直接给内置 IP，别去问系统 DNS（只会拿到污染结果）
        javIpsByHost[host.lowercase()]?.let { return it.distinct() }

        if (SettingsRepository.useBuiltInHosts && HANIME_HOSTNAME.contains(host)) {
            val customIps = resolveCustomIps()
            if (!customIps.isNullOrEmpty()) {
                return customIps.distinct()
            }
            return cloudFlareIps.distinct()
        }

        return runCatching {
            Dns.SYSTEM.lookup(host).map { it.hostAddress }.distinct()
        }.getOrElse {
            it.printStackTrace()
            emptyList()
        }
    }

    @Volatile
    private var cachedCustomIps: List<String>? = null

    @Volatile
    private var cachedCustomIpsRaw: String? = null

    private fun resolveCustomIps(): List<String>? {
        val raw = SettingsRepository.customHostsData
        if (raw.isBlank()) return null
        if (raw == cachedCustomIpsRaw && cachedCustomIps != null) {
            return cachedCustomIps
        }
        val result = parseCustomIps(raw)
        cachedCustomIpsRaw = raw
        cachedCustomIps = result
        return result
    }

}
