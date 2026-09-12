package io.github.daisukikaffuchino.han1meviewer.logic.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个 CDN 中转节点。
 *
 * 「节点」= 一台跑着中转服务的机器，协议是 `https://<host>:<port>/r/<secret>/<base64url(原URL)>`。
 * [secret] 是路径口令（服务器用它确认「这是给自己用的请求」），不是加密密钥。
 *
 * ## 为什么证书必须是真证书
 *
 * 内置节点用的是自签证书，且**公钥被钉死在 APK 里**（见
 * [io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay.trustManager]）。
 * 自签证书的公钥是跟 IP 绑的，没法平移到别的机器上，所以**自定义节点不能用自签** ——
 * 请用域名 + 真证书（Let's Encrypt 即可）。用自签的结果是明确的 TLS 校验失败，
 * 而不是「悄悄不加密」，这是刻意的：宁可报错，也不做「信任一切」的降级。
 */
@Serializable
data class RelayNode(
    val id: String,
    val host: String,
    val port: Int,
    val secret: String,
    /** 用户自定义的显示名，可空。 */
    val label: String = "",
) {
    /** 稳定的去重键：同 host+port+secret 视为同一个节点。 */
    val key: String get() = "$host:$port:$secret"

    val displayHost: String get() = if (port == 443) host else "$host:$port"

    val pingUrl: String get() = "https://$host:$port/ping"

    val baseUrl: String get() = "https://$host:$port"

    companion object {

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 从设置里读出来的原始字符串解析节点列表。任何脏数据都退化成空列表，不抛。 */
        fun decodeList(raw: String?): List<RelayNode> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<RelayNode>>(raw) }
                .getOrDefault(emptyList())
                .distinctBy { it.key }
        }

        fun encodeList(nodes: List<RelayNode>): String =
            runCatching { json.encodeToString(nodes) }.getOrDefault("")

        /**
         * 校验用户输入。
         *
         * 返回错误原因的资源 id；`null` 表示合法。放在模型层而不是 UI 层，
         * 是因为「什么算合法节点」和「怎么显示错误」是两件事。
         */
        fun validate(host: String, port: Int, secret: String): RelayNodeValidation {
            val h = host.trim()
            if (h.isBlank()) return RelayNodeValidation.EmptyHost
            if (h.any { it.isWhitespace() }) return RelayNodeValidation.InvalidHost
            if (h.startsWith("http://") || h.startsWith("https://") || h.contains('/')) {
                return RelayNodeValidation.InvalidHost
            }
            if (port !in 1..65535) return RelayNodeValidation.InvalidPort
            if (secret.isBlank()) return RelayNodeValidation.EmptySecret
            if (secret.any { it.isWhitespace() } || secret.contains('/')) {
                return RelayNodeValidation.InvalidSecret
            }
            return RelayNodeValidation.Ok
        }
    }
}

/** 节点输入校验结果。 */
enum class RelayNodeValidation {
    Ok, EmptyHost, InvalidHost, InvalidPort, EmptySecret, InvalidSecret, Duplicate;
}

/**
 * 一个节点的健康检查结论。
 *
 * [latencyMs] 为 `-1` 表示不可达。三次探活取中位数，比单次稳 —— 一次抖动不该决定
 * 「这台机器到底能不能用」。
 */
data class RelayNodeHealth(
    val nodeId: String,
    val healthy: Boolean,
    val latencyMs: Int,
    val checkedAt: Long,
    /** 连续失败次数。达到阈值才会把节点从自动优选里踢出去。 */
    val consecutiveFailures: Int = 0,
)
