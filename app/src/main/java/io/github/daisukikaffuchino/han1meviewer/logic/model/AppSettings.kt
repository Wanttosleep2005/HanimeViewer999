package io.github.daisukikaffuchino.han1meviewer.logic.model

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
    val domainName: String = "https://hanime1.me/",
    /** 当前数据源，默认仍走 hanime1.me。 */
    val siteSource: SiteSource = SiteSource.Hanime1,
    val selectedBaseUrl: String = "https://hanime1.me/",
    val useCustomMirrorSite: Boolean = false,
    val customMirrorSite: String = "",
    val appendCustomMirrorPath: Boolean = true,
    val useBuiltInHosts: Boolean = false,
    val customHostsData: String = "",
    val useDoH: Boolean = false,
    val dohPreset: String = "alidns",
    val dohCustomUrl: String = "",
    val dohBootstrapIps: String = "",
    val dohTimeoutSeconds: Int = 10,
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
