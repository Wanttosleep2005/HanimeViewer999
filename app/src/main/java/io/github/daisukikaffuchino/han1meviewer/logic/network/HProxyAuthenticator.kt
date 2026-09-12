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
     * 所以这里只能设**全局**默认实现。App 是单进程，且我们只对「发给自己配的那个代理」的
     * 请求应答，副作用可控。
     */
    private val socks = object : java.net.Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val username = SettingsRepository.proxyUsername
            if (username.isBlank()) return null
            // getRequestingHost()/getRequestingPort() 是 Java 侧的 protected 方法，
            // **只能在子类内部调用** —— 挪到外面的普通函数里会直接编译不过。
            if (!isOurProxyRequest(getRequestingHost(), getRequestingPort())) return null
            return PasswordAuthentication(username, SettingsRepository.proxyPassword.toCharArray())
        }
    }

    /**
     * 这个认证请求是不是「发给我们自己配置的那个代理」的。
     *
     * ⭐ **绝对不要用 `requestorType` 来判断。**
     * 直觉上应该写 `if (requestorType != RequestorType.PROXY) return null`，
     * 但实测（JDK 21，SOCKS5 路径）：`getPasswordAuthentication()` 里
     * `requestorType` **恒为 `SERVER`**，永远不会是 `PROXY` —— 那个守卫会
     * **每次命中、永远返回 null，把 SOCKS5 认证彻底废掉**。
     *
     * 更阴的是失败姿势：`getPasswordAuthentication()` 返回 null 之后，
     * `SocksSocketImpl` **不会**退回匿名，而是拿**系统用户名**（桌面端实测是
     * `user.name`，密码为空）去发起 RFC 1929 协商。服务端看到的是「有凭据、
     * 但不匹配」，于是回认证失败 —— 日志里表现为
     * **每次连接都 auth failure，客户端侧只看到「网络错误」**，
     * 完全看不出是凭据根本没发出去。
     *
     * 可用的判据是 `requestingHost` / `requestingPort`（实测这两个有值，
     * 分别等于代理的 host 与 port）。两者都拿不到时**放行**——宁可发凭据，
     * 也别再让一个「守卫」把整个功能静默干掉。
     */
    private fun isOurProxyRequest(host: String?, port: Int): Boolean {
        val h = host.orEmpty()
        if (h.isEmpty() && port <= 0) {
            LogUtil.w(TAG, "SOCKS5 认证请求缺少 host/port，无法比对，按代理请求处理")
            return true
        }
        return h == SettingsRepository.proxyIp || port == SettingsRepository.proxyPort
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
