package io.github.daisukikaffuchino.han1meviewer.logic.model

/** 分片下载允许的最大连接数（与设置页滑杆上限一致）。 */
const val MAX_DOWNLOAD_SEGMENTS = 8

val DOWNLOAD_SPEED_BYTES = longArrayOf(
    0L,
    128 * 1024L,
    256 * 1024L,
    512 * 1024L,
    1024 * 1024L,
    2048 * 1024L,
    4096 * 1024L,
    8192 * 1024L,
    10240 * 1024L,
)

fun normalizeLegacySlideSensitivity(storedValue: Int?): Int = when (storedValue) {
    1, 2 -> 6
    3, 4 -> 5
    5 -> 4
    6 -> 3
    7 -> 2
    8, 9 -> 1
    else -> AppSettings().slideSensitivity
}

enum class ThemeMode(val value: String) {
    Light("always_off"),
    Dark("always_on"),
    System("follow_system");

    companion object {
        fun fromValue(value: String): ThemeMode = entries.firstOrNull { it.value == value } ?: Light
    }
}

enum class ThemeAccent(val id: Int) {
    Pink(0), Green(1), Yellow(2), Blue(3);

    companion object {
        fun fromId(id: Int): ThemeAccent = entries.firstOrNull { it.id == id } ?: Pink
    }
}

enum class PaletteStyle(val id: Int) {
    TonalSpot(1), Neutral(2), Vibrant(3), Expressive(4), Rainbow(5), FruitSalad(6),
    Fidelity(7), Content(8);

    companion object {
        fun fromId(id: Int): PaletteStyle = entries.firstOrNull { it.id == id } ?: TonalSpot
    }
}

enum class PlayerKernel(val value: String) {
    MediaPlayer("MediaPlayer"), ExoPlayer("ExoPlayer"), MpvPlayer("MpvPlayer");

    companion object {
        fun fromValue(value: String): PlayerKernel = entries.firstOrNull { it.value == value } ?: ExoPlayer
        fun fromPreference(value: String): PlayerKernel = fromValue(value)
    }
}

enum class ProxyType(val id: Int) {
    Direct(0), System(1), Http(2), Socks(3);

    companion object {
        fun fromId(id: Int): ProxyType = entries.firstOrNull { it.id == id } ?: System
    }
}

enum class DisplayDensity(val percent: Int, val scale: Float) {
    Compact(75, 0.75f),
    Default(100, 1f),
    Comfortable(125, 1.25f);

    companion object {
        fun fromPercent(percent: Int): DisplayDensity =
            entries.firstOrNull { it.percent == percent } ?: Default
    }
}

enum class VideoLandscapeLayoutStyle(val value: String) {
    Classic("classic"),
    DualPane("dual_pane");

    companion object {
        fun fromValue(value: String): VideoLandscapeLayoutStyle =
            entries.firstOrNull { it.value == value } ?: Classic
    }
}

/**
 * 数据源：决定首页 / 搜索 / 播放页的数据从哪个站点来。
 *
 * - [Hanime1]：hanime1.me 及其镜像（里番），默认值，行为与旧版完全一致。
 * - [Njav]：nJAV（njavtv.com，日本 AV）。与 hanime 共用同一套 UI 与模型，
 *   只是在仓库层分流；账号相关功能（登录 / 我的清单 / 评论）仍然只支持 [Hanime1]。
 */
enum class SiteSource(val value: String) {
    Hanime1("hanime1"),
    Njav("njav");

    val isNjav: Boolean get() = this == Njav

    companion object {
        fun fromValue(value: String?): SiteSource =
            entries.firstOrNull { it.value == value } ?: Hanime1

        fun fromPreference(value: String?): SiteSource = fromValue(value)
    }
}

data class AppSettings(
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.Light,
    val useDynamicColor: Boolean = false,
    val themeAccent: ThemeAccent = ThemeAccent.Pink,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val fakeLauncherIcon: String = DEFAULT_LAUNCHER_ICON,
    val allowPipMode: Boolean = true,
    val useLockScreen: Boolean = false,
    val secureMode: Boolean = false,
    val disableComments: Boolean = false,
    val hapticFeedbackEnabled: Boolean = false,
    val disablePredictiveBack: Boolean = false,
    val tabletMode: Boolean = false,
    val largeScreenTabletModeHintShown: Boolean = false,
    val videoLandscapeLayoutStyle: VideoLandscapeLayoutStyle = VideoLandscapeLayoutStyle.Classic,
    // 【自用构建】跳过「使用须知」20 秒强制阅读与「应用来源」校验：
    // 默认即为已接受 / 已验证，启动后不会再弹出任何拦截对话框。
    // 这两项同时被 HomePageViewModel 用作数据加载开关，置 true 可保证首页照常加载。
    val usageNoticeAccepted: Boolean = true,
    val usageSourceVerified: Boolean = true,
    val usageSourcePending: Boolean = false,
    val isAlreadyLogin: Boolean = false,
    val localListNoticeDismissed: Boolean = false,
    val savedUserId: String = "",
    val loginCookie: String = "",
    val cloudFlareCookie: String = "",
    val cloudFlareCookieHost: String = "",
    /**
     * 默认镜像 = `hanime1.com`。
     *
     * ⚠️ 不是 `hanime1.me`。实测（2026-09-12）国内线路对这三个镜像按 **SNI** 阻断：
     * 同一个 Cloudflare IP 上，SNI 写 `hanime1.com` 返回 200，写 `hanime1.me` /
     * `hanimeone.me` 直接 TLS RST。详见 [io.github.daisukikaffuchino.han1meviewer.HanimeConstants.HANIME_URL]。
     */
    val domainName: String = "https://hanime1.com/",
    /** 当前数据源，默认仍走 hanime 里番。 */
    val siteSource: SiteSource = SiteSource.Hanime1,
    val selectedBaseUrl: String = "https://hanime1.com/",
    val useCustomMirrorSite: Boolean = false,
    val customMirrorSite: String = "",
    val appendCustomMirrorPath: Boolean = true,
    /**
     * 用户自建的镜像列表（JSON 数组），由
     * [io.github.daisukikaffuchino.han1meviewer.logic.network.MirrorStore] 读写。
     *
     * 与 [domainName] / [customMirrorSite] 的分工：那两个是**当前生效**的入口，
     * 这里是**候选池**。池子里的内置四项（三个 hanime 镜像 + nJAV）不落库，
     * 始终由 [io.github.daisukikaffuchino.han1meviewer.HanimeConstants] 提供，
     * 所以删光这一项也不会让用户失去入口。
     */
    val extraMirrorsJson: String = "",
    /**
     * 置顶的搜索词（JSON 数组）。置顶的排在搜索结果页历史列表最前，且不会被自动裁剪。
     *
     * 放在设置里而不是新开一张表：置顶只是一个「顺序」问题，为它加一列就要写一次
     * Room 迁移，收益不匹配。
     */
    val pinnedSearchesJson: String = "",
    val useBuiltInHosts: Boolean = false,
    val customHostsData: String = "",
    /**
     * ⚠️ 默认**开启** DoH。
     *
     * 国内系统 DNS 对本站系域名是**投毒**的（实测：`hanime1.me` → `103.246.246.144`
     * TCP 拒绝；`hanime1.com` → `154.85.102.32` 超时），不开 DoH 就只能拿到假 IP。
     * 配合 [dohPreset] 默认 `dnspod`（`doh.pub` 实测能返回真实 Cloudflare IP），
     * 安装后即可直连，无需用户手动配置。
     *
     * 注意：它与 [useBuiltInHosts] 在设置页里**互斥**（开一个会自动关另一个），
     * 这里默认走 DoH 这条更通用的路径，[useBuiltInHosts] 保持 false。
     */
    val useDoH: Boolean = true,
    /** 默认 DNSPod（`doh.pub`）：实测能对 hanime / nJAV 返回真实 IP，见 [DohConfig.presets]。 */
    val dohPreset: String = "dnspod",
    val dohCustomUrl: String = "",
    val dohBootstrapIps: String = "",
    val dohTimeoutSeconds: Int = 10,
    /**
     * 允许封面图在直连失败时借用第三方图片中转（默认开）。
     *
     * 本站两个站族的封面图都在**被封**的域名上（hanime 全部图片走 `vdownload.hembed.com`，
     * nJAV 封面走 `fourhoi.com`），纯客户端改 DNS / 改 IP 都救不了，只能借一跳。
     * 默认开启是为了「装完就能看到封面」；关掉后本站封面会显示为空（不影响别的图源）。
     *
     * 为什么仍要留这个开关：走中转意味着**每个封面图的完整 URL（含域名与视频编号）
     * 都会经过第三方服务器**。这是实打实的隐私代价，用户有权拒绝，所以设置为可关。
     *
     * 影响范围仅限图片：[io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.ImageRelayInterceptor]
     * **只对图片扩展名生效**，视频链路不会被送去第三方（中转服务本身也只处理图片）。
     */
    val allowImageRelay: Boolean = true,
    /**
     * 允许被封 CDN（hanime 视频与封面、nJAV 封面）走**自建** TLS 中转（默认开）。
     *
     * 与 [allowImageRelay] 是「同类问题的两代方案」：
     * - [allowImageRelay] 只能救图片，且会把图片 URL 交给第三方 `wsrv.nl`；
     * - 本开关走用户自己的服务器，图片和**视频**都能过，URL 不外泄。
     *
     * ⚠️ 关掉它的直接后果是**视频完全看不了**（`vdownload.hembed.com` 在内地不可达，
     * 且普通代理无效 —— TLS 的 SNI 是明文，墙在明文隧道里照样 RST，见
     * [io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay]）。
     * 留这个开关只是给「线路本来就能直连」的用户省一次绕行。
     */
    val allowCdnRelay: Boolean = true,
    /**
     * 额外的中转节点（JSON 数组，见 [io.github.daisukikaffuchino.han1meviewer.logic.network.RelayNodeStore]）。
     *
     * **内置节点不在这里** —— 它写死在代码里且不可删除，本字段只存用户自己加的。
     * 存 JSON 而不是拆成多列，是因为节点是「整条增删」的集合，拆列反而要在
     * DataStore 里模拟一张表。
     */
    val relayNodesJson: String = "",
    /** 手动指定的中转节点 id；空字符串 = 自动优选。 */
    val activeRelayNodeId: String = "",
    /**
     * 是否按健康检查结果自动优选节点（默认开）。
     *
     * 关掉后会一直用手动选中的那个（没手动选过就用内置节点），哪怕它明显更慢。
     */
    val autoSelectRelayNode: Boolean = true,
    val proxyType: ProxyType = ProxyType.System,
    val proxyIp: String = "",
    val proxyPort: Int = -1,
    /**
     * HTTP / SOCKS5 代理的认证凭据。
     *
     * 留空即「匿名代理」。**公网 VPS 上强烈建议填** —— 一个不带认证的开放代理
     * 放到公网上，几小时内就会被扫到并被当成免费跳板。
     */
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val cachedUpdateJson: String? = null,
    val ignoredVersionCode: Int = -1,
    val downloadCountLimit: Int = 2,
    val downloadSpeedLimitIndex: Int = 0,
    /**
     * 单个文件下载时开几条连接（分片并行）。
     *
     * 为什么要这个：实测（2026-09-12）这台中转服务器到国内**单条 TCP 只有 ~350 KB/s**，
     * 而**多连接并行能到 ~1050 KB/s** —— 是国际链路**按流限速**（每条流各自被卡），
     * 总带宽其实是够的。所以「换服务器」不解决问题，**开多流才是解**。
     *
     * 4 是保守值：足够拿到 3 倍收益，又不会把中转打得太满（服务器是 4H4G，
     * 中转用线程池，每个分片占一条上游连接）。
     *
     * 只在**直链 + 私有目录 + 全新下载 + 服务端确认支持 Range** 时生效；
     * HLS（只能逐片顺序拼）、SAF 目录、断点续传一律退回单连接 ——
     * 见 `HanimeDownloadWorker.downloadParallel`。
     */
    val downloadSegments: Int = 4,
    val usePrivateStorage: Boolean = true,
    val safDownloadPath: String? = null,
    val collapseDownloadedGroup: Boolean = false,
    val playerKernel: PlayerKernel = PlayerKernel.ExoPlayer,
    val enableGoogleCast: Boolean = false,
    val showBottomProgress: Boolean = true,
    val playerSpeed: Float = 1f,
    val slideSensitivity: Int = 4,
    val longPressSpeedTime: Float = 2.5f,
    val videoLanguage: String = "zhs",
    val videoQuality: String = "1080P",
    val showPlayedIndicator: Boolean = true,
    val allowResumePlayback: Boolean = true,
    /**
     * 是否**按影片**记住倍速与画质。
     *
     * 开启后，在一部影片里调过的倍速/画质会跟着这部影片存下来，下次打开这部自动恢复；
     * 没调过的仍走 [playerSpeed] / [videoQuality] 这两个全局默认值。
     * 默认开启 —— 「上次用 1.5 倍看完的番，下次打开又变回 1.0」是明显的倒退。
     */
    val rememberPerVideoPlayback: Boolean = true,
    /** 按影片的播放记忆（JSON），由 [io.github.daisukikaffuchino.han1meviewer.logic.PlaybackMemory] 读写。 */
    val perVideoPlaybackJson: String = "",
    val whenCountdownRemindSeconds: Int = 10,
    val showCommentWhenCountdown: Boolean = false,
    val hKeyframesEnable: Boolean = true,
    val sharedHKeyframesEnable: Boolean = true,
    val sharedHKeyframesUseFirst: Boolean = false,
    val mpvProfile: String = "fast",
    val enableGpuNextRenderer: Boolean = false,
    val mpvInterpolation: Boolean = false,
    val mpvDeband: Boolean = true,
    val mpvFramedrop: Boolean = true,
    val mpvHwdec: String = "Auto",
    val mpvCacheSecs: Int = 60,
    val mpvTlsVerify: Boolean = true,
    val mpvNetworkTimeout: Int = 10,
    val customMpvParams: String = "",
    val searchArtistIgnoreVideoType: Boolean = false,
    val disableMobileDataWarning: Boolean = false,
    val funLoadingHints: Boolean = true,
    val checkInEnabled: Boolean = true,
    val searchGridColumnsCompact: Int = 2,
    val searchGridColumnsMedium: Int = 3,
    val searchGridColumnsExpanded: Int = 4,
    val searchGridColumnsLarge: Int = 5,
    val horizontalCardCountNarrow: Float = 1.5f,
    val horizontalCardCountCompact: Float = 2.1f,
    val horizontalCardCountMedium: Float = 4.1f,
    val horizontalCardCountExpanded: Float = 5.1f,
    val subscriptionArtistRows: Int = 1,
    val homeCategoryOrder: List<String> = emptyList(),
    val hiddenHomeCategoryKeys: Set<String> = emptySet(),
    val alwaysShowUpdateCard: Boolean = false,
    val displayDensity: DisplayDensity = DisplayDensity.Default,
) {
    companion object {
        const val DEFAULT_LAUNCHER_ICON =
            "io.github.daisukikaffuchino.han1meviewer.LauncherAliasDefault"
    }
}
