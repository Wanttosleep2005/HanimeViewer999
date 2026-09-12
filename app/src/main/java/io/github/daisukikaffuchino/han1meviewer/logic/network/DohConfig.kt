package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository

data class DohPreset(
    val key: String,
    val title: String,
    val url: String,
    val bootstrapIps: List<String>,
)

object DohConfig {
    /**
     * ⚠️ **顺序有意义：`[0]` 是默认值，也是 [selectedPreset] 的兜底值。**
     *
     * 所以 DNSPod 必须在 `[0]`，不能是 AliDNS —— 实测 2026-09-12，`dns.alidns.com`
     * 对本站系域名返回的是**投毒结果**：
     *
     * | 查询 | AliDNS 返回 | 实测 | DNSPod 返回 | 实测 |
     * |---|---|---|---|---|
     * | `hanime1.me` | `199.59.148.106` / `31.13.70.33` | 超时 | `172.67.74.156` 等 | 真实 |
     * | `hanime1.com` | `31.13.70.33`（**Facebook 段**） | 超时 | `172.67.167.30` 等 | **200** |
     * | `njavtv.com` | `199.96.63.53` | 超时 | `104.26.7.251` 等 | **200** |
     *
     * `cloudflare-dns.com` / `dns.google` / `1.1.1.1` / `8.8.8.8` / `dns.quad9.net`
     * 在国内**直连不可用**，所以不放在前面；用户若在境外可自行选 Cloudflare。
     *
     * 把 AliDNS 放回 `[0]` 会让新装用户默认走投毒解析，等于回到「打不开」的状态。
     */
    val presets = listOf(
        DohPreset(
            key = "dnspod",
            title = "DNSPod",
            url = "https://doh.pub/dns-query",
            bootstrapIps = listOf("1.12.12.12", "120.53.53.53"),
        ),
        DohPreset(
            key = "alidns",
            title = "AliDNS",
            url = "https://dns.alidns.com/dns-query",
            bootstrapIps = listOf("223.5.5.5", "223.6.6.6"),
        ),
        DohPreset(
            key = "cloudflare",
            title = "Cloudflare",
            url = "https://cloudflare-dns.com/dns-query",
            bootstrapIps = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
        ),
    )

    fun selectedPreset(): DohPreset = presets.firstOrNull { it.key == SettingsRepository.dohPreset } ?: presets.first()

    fun customUrl(): String = SettingsRepository.dohCustomUrl.trim()

    fun bootstrapIps(): List<String> {
        val customBootstrapIps = SettingsRepository.dohBootstrapIps
            .split(',', '\n', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (customBootstrapIps.isNotEmpty()) return customBootstrapIps
        if (SettingsRepository.dohPreset == "custom") return emptyList()
        return selectedPreset().bootstrapIps
    }

    fun timeoutSeconds(): Int = SettingsRepository.dohTimeoutSeconds.coerceIn(1, 60)

    fun resolveUrl(): String? {
        if (!SettingsRepository.useDoH) return null
        return when (SettingsRepository.dohPreset) {
            "custom" -> customUrl().takeIf { it.isNotBlank() }
            else -> selectedPreset().url
        }
    }
}
