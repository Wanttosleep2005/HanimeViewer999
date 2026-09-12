package io.github.daisukikaffuchino.han1meviewer.logic.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个可选镜像站。
 *
 * 「镜像」= 与官方站同源、可互换使用的入口域名。与
 * [io.github.daisukikaffuchino.han1meviewer.logic.network.RelayNode] 的区别值得说清：
 *
 * - **镜像**决定「去哪儿取页面」（`hanime1.com` / `njavtv.com`），是**站点的入口**；
 * - **中转节点**决定「被封的视频/封面怎么绕出去」，是**链路的出口**。
 *
 * 两者正交：可以「用 .com 取页面 + 用自建节点取视频」，也可以单用其一。
 *
 * [builtIn] 的镜像来自 [io.github.daisukikaffuchino.han1meviewer.HanimeConstants]，
 * 不可删除 —— 删光自建镜像后至少还能回到「出厂可用」的入口。
 */
@Serializable
data class MirrorNode(
    val id: String,
    /** 带尾斜杠的根地址，例如 `https://hanime1.com/`。 */
    val url: String,
    /** 用户自定义的显示名，可空。 */
    val label: String = "",
    val builtIn: Boolean = false,
) {
    /** 主机名，解析失败时退化成原串（不抛）。 */
    val host: String
        get() = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    val displayHost: String get() = host

    /** 稳定的去重键：同 URL 视为同一个镜像。 */
    val key: String get() = url.trimEnd('/')

    companion object {

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 从设置里读出来的原始字符串解析镜像列表。任何脏数据都退化成空列表，不抛。 */
        fun decodeList(raw: String?): List<MirrorNode> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<MirrorNode>>(raw) }
                .getOrDefault(emptyList())
                .distinctBy { it.key }
        }

        fun encodeList(nodes: List<MirrorNode>): String =
            runCatching { json.encodeToString(nodes) }.getOrDefault("")

        /**
         * 归一化用户输入的镜像地址。
         *
         * 只放行 `https`：本站系域名都是 https，允许 http 等于允许明文链路被替换内容。
         * 返回 `null` 表示非法，调用方据此回显错误。
         */
        fun normalize(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return null
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host
            if (host.isNullOrBlank()) return null
            if (host.any { it.isWhitespace() }) return null
            if (!uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) return null
            // 只保留 scheme://host[:port]，路径一律丢掉 —— 镜像的「页面路径」由站点自己决定，
            // 用户填的路径只会让后续拼接出不可预期的 URL。
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "https://$host$port/"
        }
    }
}

/** 镜像输入校验结果。 */
enum class MirrorValidation {
    Ok, Empty, Invalid, Duplicate;
}

/**
 * 一次镜像探测的结论。
 *
 * [latencyMs] 是**首字节耗时**（TTFB），为 `-1` 表示不可达。
 * 用 TTFB 而不是「整页下载完」：首页体积几百 KB，不同镜像返回的 HTML 大小并不一致，
 * 拿总耗时比会混入「谁返回的页更大」这个与链路无关的变量。
 */
data class MirrorProbe(
    val mirrorId: String,
    val reachable: Boolean,
    val latencyMs: Int,
    /** HTTP 状态码；未取到时为 -1。 */
    val httpCode: Int,
    val checkedAt: Long,
)
