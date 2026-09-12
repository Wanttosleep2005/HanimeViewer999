package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.utils.LogUtil
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.PasswordAuthentication

/**
 * 代理认证（用户名 / 密码）。
 *
 * 为什么要有这个：[HProxySelector] 只能告诉 OkHttp「请求往哪个 socket 发」，
 * 而**认证**完全是另一条路，而且 HTTP 代理和 SOCKS5 代理走的是两套机制：
 *
 * | 代理类型 | 认证由谁发起 | 应用侧怎么接 |
 * |---|---|---|
 * | HTTP / HTTPS(CONNECT) | 代理返回 `407 Proxy Authentication Required` | OkHttp 的 `proxyAuthenticator` |
 * | SOCKS5 | `java.net.SocksSocketImpl` 内部协商（RFC 1929） | 只能设全局 `java.net.Authenticator` |
 *
 * OkHttp **管不到 SOCKS5 的认证**：那一段发生在 `Socket` 建连时，OkHttp 根本看不到。
 * 所以 SOCKS5 只能走 `Authenticator.setDefault`，需要一个进程级安装点，
 * 见 [installSocksAuthenticator]（由 `HanimeApplication.onCreate` 调用）。
 *
 * ⚠️ 别把凭据塞进 `HProxySelector`：那里只有「选哪个代理」的语义，
 * 而且 `select()` 会被高频调用，每次拼一次 Basic 头是纯浪费。
 */
object HProxyAuthenticator {

    private const val TAG = "HProxyAuth"

    private fun hasCredentials() =
        SettingsRepository.proxyUsername.isNotBlank()

    private fun basicCredentials() =
        Credentials.basic(SettingsRepository.proxyUsername, SettingsRepository.proxyPassword)

    /**
     * HTTP 代理的认证钩子，挂到 `OkHttpClient.Builder().proxyAuthenticator(...)`。
     *
     * 只在「代理确实要凭据」时才应答：没配用户名就直接返回 null，
     * 免得给匿名代理加上一个莫名其妙的 `Proxy-Authorization` 反而被拒。
     * 同一个请求只会重试一次（已有 `Proxy-Authorization` 头就不再应答），
     * 否则凭据错误时代理会一直 407，OkHttp 就会陷入循环。
     */
    val http: Authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (!hasCredentials()) return null
            if (response.request.header("Proxy-Authorization") != null) {
                LogUtil.w(TAG, "代理认证失败：用户名或密码不对？")
                return null
            }
            return response.request.newBuilder()
                .header("Proxy-Authorization", basicCredentials())
                .build()
        }
    }

    /**
     * SOCKS5 的用户名/密码（RFC 1929）。
     *
     * `SocksSocketImpl` 在代理要求认证时会调用 `Authenticator.requestPasswordAuthentication`，
     * 所以这里只能设**全局**默认实现。App 是单进程，且我们只对 `PROXY` 类型的请求应答，
     * 副作用可控。
     */
    private val socks = object : java.net.Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestorType != RequestorType.PROXY) return null
            val username = SettingsRepository.proxyUsername
            if (username.isBlank()) return null
            return PasswordAuthentication(username, SettingsRepository.proxyPassword.toCharArray())
        }
    }

    /**
     * 安装 SOCKS5 认证器。**幂等**，重复调用没有副作用。
     *
     * 必须在 `MPVLib.init()` / 任何建连之前调用 —— 但只有第一次 SOCKS5 连接才真正用得上，
     * 所以放在 `Application.onCreate` 里最省事。
     */
    fun installSocksAuthenticator() {
        java.net.Authenticator.setDefault(socks)
        LogUtil.d(TAG, "已安装 SOCKS5 认证器")
    }
}
