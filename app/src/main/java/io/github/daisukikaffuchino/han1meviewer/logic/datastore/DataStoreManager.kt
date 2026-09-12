package io.github.daisukikaffuchino.han1meviewer.logic.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.daisukikaffuchino.han1meviewer.logic.model.AppLanguage
import io.github.daisukikaffuchino.han1meviewer.logic.model.AppSettings
import io.github.daisukikaffuchino.han1meviewer.logic.model.DisplayDensity
import io.github.daisukikaffuchino.han1meviewer.logic.model.PaletteStyle
import io.github.daisukikaffuchino.han1meviewer.logic.model.PlayerKernel
import io.github.daisukikaffuchino.han1meviewer.logic.model.ProxyType
import io.github.daisukikaffuchino.han1meviewer.logic.model.SettingsStore
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.logic.model.ThemeAccent
import io.github.daisukikaffuchino.han1meviewer.logic.model.ThemeMode
import io.github.daisukikaffuchino.han1meviewer.logic.model.VideoLandscapeLayoutStyle
import io.github.daisukikaffuchino.han1meviewer.logic.model.DOWNLOAD_SPEED_BYTES
import io.github.daisukikaffuchino.han1meviewer.logic.model.MAX_DOWNLOAD_SEGMENTS
import io.github.daisukikaffuchino.han1meviewer.logic.model.normalizeLegacySlideSensitivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object DataStoreManager : SettingsStore {
    private const val FILE_NAME = "settings"
    private const val SLIDE_MIGRATED = "slide_sensitivity_v2_migrated"
    private const val NETWORK_FIX_MIGRATED = "network_connectivity_fix_v1"
    private val defaults = AppSettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableSettings = MutableStateFlow(defaults)
    override val settings: StateFlow<AppSettings> = mutableSettings

    private lateinit var dataStore: DataStore<Preferences>
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            dataStore = PreferenceDataStoreFactory.create(
                migrations = listOf(
                    SharedPreferencesMigration(appContext, "${appContext.packageName}_preferences"),
                    SharedPreferencesMigration(appContext, appContext.packageName),
                ),
                produceFile = { appContext.preferencesDataStoreFile(FILE_NAME) },
            )
            runBlocking(Dispatchers.IO) {
                normalizeLegacySlideSensitivity()
                applyNetworkConnectivityFix()
                val initial = dataStore.data.first().toAppSettings()
                dataStore.edit { it.write(initial) }
                mutableSettings.value = initial
            }
            initialized = true
            scope.launch {
                dataStore.data.map { it.toAppSettings() }.collect { mutableSettings.value = it }
            }
        }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        check(initialized) { "DataStoreManager must be initialized before use" }
        lateinit var updated: AppSettings
        dataStore.edit {
            updated = transform(it.toAppSettings())
            it.write(updated)
        }
        mutableSettings.value = updated
    }

    private val current: AppSettings get() = settings.value

    suspend fun restoreBackup(values: Map<String, Any>) {
        lateinit var restored: AppSettings
        dataStore.edit { preferences ->
            values.filterKeys { it !in AUTH_KEYS }.forEach { (name, value) -> preferences.putRaw(name, value) }
            restored = preferences.toAppSettings()
            preferences.write(restored)
        }
        mutableSettings.value = restored
    }

    fun exportBackup(): Map<String, Any> = current.toMap().filterKeys { it !in AUTH_KEYS }

    /**
     * 一次性把老用户从「默认打不开」的配置里挪出来。
     *
     * 背景：`use_doh` / `doh_preset` / `domain_name` 都是**持久化**的，改 [AppSettings]
     * 的默认值**只对新装生效**。老用户（含 mod 7.5 及更早）盘里躺着的是
     * `use_doh=false` + `doh_preset=alidns` + `domain_name=https://hanime1.me/`，
     * 也就是「系统 DNS 被投毒 + 阿里 DoH 也返回投毒 IP + 域名本身被 SNI 阻断」，
     * 三样占全，装上新版依然打不开。所以必须显式迁移一次。
     *
     * 迁移规则（**只动「还是出厂值」的项，绝不覆盖用户刻意改过的设置**）：
     *
     * 1. 域名仍是老的出厂值 `hanime1.me` → 换成唯一可直连的 `hanime1.com`。
     *    用 `use_custom_mirror_site` / `site_source` 兜住：自定义镜像与 nJAV 数据源都不碰。
     * 2. 没开「内置域名」时才开 DoH。开了内置域名的用户是**故意**选的另一条路
     *    （两者在设置页互斥，自动打开 DoH 会把他的选择关掉），所以直接跳过。
     * 3. `doh_preset` 还是老的出厂值 `alidns` → 换成 `dnspod`。`custom` / `cloudflare`
     *    等值一律不动（那是用户自己选的）。
     *
     * 用 [NETWORK_FIX_MIGRATED] 打标，只跑一次：否则用户之后手动关掉 DoH
     * 或切回 `hanime1.me`，下次启动又会被强行改回去。
     */
    private suspend fun applyNetworkConnectivityFix() {
        dataStore.edit { preferences ->
            if (preferences.bool(NETWORK_FIX_MIGRATED, false)) return@edit

            val usingCustomMirror = preferences.bool("use_custom_mirror_site", false)
            val onNjav = preferences.string("site_source", "hanime1") == "njav"
            val useBuiltInHosts = preferences.bool("use_built_in_hosts", false)

            // 1) 域名：仅在「仍是老出厂值」且没走自定义镜像 / nJAV 时改。
            if (!usingCustomMirror && !onNjav) {
                val legacy = "https://hanime1.me/"
                if (preferences.string("domain_name", legacy).trimEnd('/') == legacy.trimEnd('/')) {
                    preferences[stringPreferencesKey("domain_name")] = "https://hanime1.com/"
                }
                // selectedBaseUrl 是「切回 hanime 时用哪个镜像」，同样是老值时一并校准，
                // 否则用户进了 nJAV 再切回来，会切回那个被 SNI 阻断的 hanime1.me。
                if (preferences.string("selectedBaseUrl", legacy).trimEnd('/') == legacy.trimEnd('/')) {
                    preferences[stringPreferencesKey("selectedBaseUrl")] = "https://hanime1.com/"
                }
            }

            // 2) 只在用户没有主动选「内置域名」时打开 DoH（两者互斥，理由见 KDoc）。
            if (!useBuiltInHosts) {
                preferences[booleanPreferencesKey("use_doh")] = true
            }

            // 3) 预设：老的出厂值 alidns（实测返回投毒 IP）→ dnspod。用户自选的其它值不动。
            if (preferences.string("doh_preset", "alidns") == "alidns") {
                preferences[stringPreferencesKey("doh_preset")] = "dnspod"
            }

            preferences[booleanPreferencesKey(NETWORK_FIX_MIGRATED)] = true
        }
    }

    private suspend fun normalizeLegacySlideSensitivity() {
        dataStore.edit { preferences ->
            if (preferences.bool(SLIDE_MIGRATED, false)) return@edit
            val stored = preferences.intOrNull("slide_sensitivity")
            preferences[intPreferencesKey("slide_sensitivity")] =
                normalizeLegacySlideSensitivity(stored)
            preferences[booleanPreferencesKey(SLIDE_MIGRATED)] = true
        }
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        appLanguage = AppLanguage.fromPreference(string("app_language", defaults.appLanguage.preferenceValue)),
        themeMode = ThemeMode.fromValue(string("use_dark_mode", defaults.themeMode.value)),
        useDynamicColor = bool("use_dynamic_color", defaults.useDynamicColor),
        themeAccent = ThemeAccent.fromId(int("theme_accent_color", defaults.themeAccent.id)),
        paletteStyle = PaletteStyle.fromId(int("app_palette_style", defaults.paletteStyle.id)),
        fakeLauncherIcon = string("pref_fake_launcher_icon", defaults.fakeLauncherIcon),
        allowPipMode = bool("allow_pip_mode", defaults.allowPipMode),
        useLockScreen = bool("use_lock_screen", defaults.useLockScreen),
        secureMode = bool("secure_mode", defaults.secureMode),
        disableComments = bool("disable_comments", defaults.disableComments),
        hapticFeedbackEnabled = bool("haptic_feedback_enabled", defaults.hapticFeedbackEnabled),
        disablePredictiveBack = bool("disable_predictive_back", defaults.disablePredictiveBack),
        tabletMode = bool("tablet_mode", defaults.tabletMode),
        largeScreenTabletModeHintShown = bool(
            "large_screen_tablet_mode_hint_shown",
            defaults.largeScreenTabletModeHintShown,
        ),
        videoLandscapeLayoutStyle = VideoLandscapeLayoutStyle.fromValue(
            string("video_landscape_layout_style", defaults.videoLandscapeLayoutStyle.value)
        ),
        usageNoticeAccepted = bool("usage_notice_accepted_v2", defaults.usageNoticeAccepted),
        usageSourceVerified = bool("usage_source_verified", defaults.usageSourceVerified),
        usageSourcePending = bool("usage_source_pending", defaults.usageSourcePending),
        isAlreadyLogin = bool("already_login", defaults.isAlreadyLogin),
        localListNoticeDismissed = bool(
            "local_list_notice_dismissed",
            defaults.localListNoticeDismissed,
        ),
        savedUserId = string("saved_user_id", defaults.savedUserId),
        loginCookie = string("cookie", defaults.loginCookie),
        cloudFlareCookie = string("cf_cookie", defaults.cloudFlareCookie),
        cloudFlareCookieHost = string("cf_cookie_host", defaults.cloudFlareCookieHost).lowercase(),
        domainName = string("domain_name", defaults.domainName), selectedBaseUrl = string("selectedBaseUrl", defaults.selectedBaseUrl),
        siteSource = SiteSource.fromValue(string("site_source", defaults.siteSource.value)),
        useCustomMirrorSite = bool("use_custom_mirror_site", defaults.useCustomMirrorSite), customMirrorSite = string("custom_mirror_site", defaults.customMirrorSite),
        appendCustomMirrorPath = bool("append_custom_mirror_path", defaults.appendCustomMirrorPath), extraMirrorsJson = string("extra_mirrors_json", defaults.extraMirrorsJson),
        useBuiltInHosts = bool("use_built_in_hosts", defaults.useBuiltInHosts),
        customHostsData = string("custom_hosts_data", defaults.customHostsData), useDoH = bool("use_doh", defaults.useDoH), dohPreset = string("doh_preset", defaults.dohPreset),
        dohCustomUrl = string("doh_custom_url", defaults.dohCustomUrl), dohBootstrapIps = string("doh_bootstrap_ips", defaults.dohBootstrapIps), dohTimeoutSeconds = int("doh_timeout_seconds", defaults.dohTimeoutSeconds),
        allowImageRelay = bool("allow_image_relay", defaults.allowImageRelay),
        allowCdnRelay = bool("allow_cdn_relay", defaults.allowCdnRelay),
        relayNodesJson = string("relay_nodes_json", defaults.relayNodesJson),
        activeRelayNodeId = string("active_relay_node_id", defaults.activeRelayNodeId),
        autoSelectRelayNode = bool("auto_select_relay_node", defaults.autoSelectRelayNode),
        proxyType = ProxyType.fromId(int("proxy_type", defaults.proxyType.id)), proxyIp = string("proxy_ip", defaults.proxyIp), proxyPort = int("proxy_port", defaults.proxyPort),
        cachedUpdateJson = nullableString("app_update_cached_json"), ignoredVersionCode = int("app_update_ignored_version_code", defaults.ignoredVersionCode),
        downloadCountLimit = int("download_count_limit", defaults.downloadCountLimit), downloadSpeedLimitIndex = intInRange("download_speed_limit", defaults.downloadSpeedLimitIndex, DOWNLOAD_SPEED_BYTES.indices),
        downloadSegments = intInRange("download_segments", defaults.downloadSegments, 1..MAX_DOWNLOAD_SEGMENTS),
        usePrivateStorage = bool("use_private_storage", defaults.usePrivateStorage), safDownloadPath = nullableString("saf_download_path"), collapseDownloadedGroup = bool("collapse_downloaded_group", defaults.collapseDownloadedGroup),
        playerKernel = PlayerKernel.fromValue(string("switch_player_kernel", defaults.playerKernel.value)), enableGoogleCast = bool("enable_google_cast", defaults.enableGoogleCast), showBottomProgress = bool("show_bottom_progress", defaults.showBottomProgress),
        playerSpeed = floatString("player_speed", defaults.playerSpeed), slideSensitivity = intInRange("slide_sensitivity", defaults.slideSensitivity, 1..7), longPressSpeedTime = floatString("long_press_speed_times", defaults.longPressSpeedTime),
        videoLanguage = string("video_language", defaults.videoLanguage), videoQuality = string("default_video_quality", defaults.videoQuality), showPlayedIndicator = bool("show_played_indicator", defaults.showPlayedIndicator),
        allowResumePlayback = bool("allow_resume_playback", defaults.allowResumePlayback), whenCountdownRemindSeconds = int("when_countdown_remind", defaults.whenCountdownRemindSeconds),
        showCommentWhenCountdown = bool("show_comment_when_countdown", defaults.showCommentWhenCountdown), hKeyframesEnable = bool("h_keyframes_enable", defaults.hKeyframesEnable),
        sharedHKeyframesEnable = bool("shared_h_keyframes_enable", defaults.sharedHKeyframesEnable), sharedHKeyframesUseFirst = bool("shared_h_keyframes_use_first", defaults.sharedHKeyframesUseFirst),
        mpvProfile = string("mpv_profile", defaults.mpvProfile), enableGpuNextRenderer = bool("mpv_gpu_next_render", defaults.enableGpuNextRenderer), mpvInterpolation = bool("mpv_interpolation", defaults.mpvInterpolation),
        mpvDeband = bool("mpv_deband", defaults.mpvDeband), mpvFramedrop = bool("mpv_framedrop", defaults.mpvFramedrop), mpvHwdec = string("mpv_hwdecx", defaults.mpvHwdec),
        mpvCacheSecs = int("mpv_cache_secs", defaults.mpvCacheSecs), mpvTlsVerify = bool("mpv_tls_verify", defaults.mpvTlsVerify), mpvNetworkTimeout = int("mpv_network_timeout", defaults.mpvNetworkTimeout), customMpvParams = string("mpv_custom_parameters", defaults.customMpvParams),
        searchArtistIgnoreVideoType = bool("search_artist_ignore_video_type", defaults.searchArtistIgnoreVideoType), disableMobileDataWarning = bool("disable_mobile_data_warning", defaults.disableMobileDataWarning),
        funLoadingHints = bool("fun_loading_hints", defaults.funLoadingHints), checkInEnabled = bool("check_in_enabled", defaults.checkInEnabled),
        searchGridColumnsCompact = int("search_grid_columns_compact", defaults.searchGridColumnsCompact), searchGridColumnsMedium = int("search_grid_columns_medium", defaults.searchGridColumnsMedium),
        searchGridColumnsExpanded = int("search_grid_columns_expanded", defaults.searchGridColumnsExpanded), searchGridColumnsLarge = int("search_grid_columns_large", defaults.searchGridColumnsLarge),
        horizontalCardCountNarrow = floatString("horizontal_card_count_narrow", defaults.horizontalCardCountNarrow), horizontalCardCountCompact = floatString("horizontal_card_count_compact", defaults.horizontalCardCountCompact),
        horizontalCardCountMedium = floatString("horizontal_card_count_medium", defaults.horizontalCardCountMedium), horizontalCardCountExpanded = floatString("horizontal_card_count_expanded", defaults.horizontalCardCountExpanded),
        subscriptionArtistRows = intInRange("subscription_artist_rows", defaults.subscriptionArtistRows, 1..3),
        homeCategoryOrder = nullableString("home_category_order")?.split(',')?.filter(String::isNotBlank).orEmpty(),
        hiddenHomeCategoryKeys = nullableString("home_category_hidden")?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty(),
        alwaysShowUpdateCard = bool("developer_always_show_update_card", defaults.alwaysShowUpdateCard),
        displayDensity = DisplayDensity.fromPercent(int("developer_display_density_percent", defaults.displayDensity.percent)),
    )

    private fun MutablePreferences.write(value: AppSettings) {
        remove(stringPreferencesKey("app_update_cached_json"))
        remove(stringPreferencesKey("saf_download_path"))
        value.toMap().forEach { (name, raw) -> putRaw(name, raw) }
        this[booleanPreferencesKey(SLIDE_MIGRATED)] = true
    }

    private fun AppSettings.toMap(): Map<String, Any> = buildMap {
        put("app_language", appLanguage.preferenceValue); put("use_dark_mode", themeMode.value); put("use_dynamic_color", useDynamicColor); put("theme_accent_color", themeAccent.id); put("app_palette_style", paletteStyle.id)
        put("pref_fake_launcher_icon", fakeLauncherIcon); put("allow_pip_mode", allowPipMode); put("use_lock_screen", useLockScreen); put("secure_mode", secureMode); put("disable_comments", disableComments); put("haptic_feedback_enabled", hapticFeedbackEnabled); put("disable_predictive_back", disablePredictiveBack); put("tablet_mode", tabletMode); put("large_screen_tablet_mode_hint_shown", largeScreenTabletModeHintShown); put("video_landscape_layout_style", videoLandscapeLayoutStyle.value)
        put("usage_notice_accepted_v2", usageNoticeAccepted); put("usage_source_verified", usageSourceVerified); put("usage_source_pending", usageSourcePending); put("already_login", isAlreadyLogin); put("local_list_notice_dismissed", localListNoticeDismissed); put("saved_user_id", savedUserId); put("cookie", loginCookie); put("cf_cookie", cloudFlareCookie); put("cf_cookie_host", cloudFlareCookieHost)
        put("domain_name", domainName); put("site_source", siteSource.value); put("selectedBaseUrl", selectedBaseUrl); put("use_custom_mirror_site", useCustomMirrorSite); put("custom_mirror_site", customMirrorSite); put("append_custom_mirror_path", appendCustomMirrorPath); put("extra_mirrors_json", extraMirrorsJson); put("use_built_in_hosts", useBuiltInHosts); put("custom_hosts_data", customHostsData); put("use_doh", useDoH); put("doh_preset", dohPreset); put("doh_custom_url", dohCustomUrl); put("doh_bootstrap_ips", dohBootstrapIps); put("doh_timeout_seconds", dohTimeoutSeconds); put("allow_image_relay", allowImageRelay); put("allow_cdn_relay", allowCdnRelay); put("relay_nodes_json", relayNodesJson); put("active_relay_node_id", activeRelayNodeId); put("auto_select_relay_node", autoSelectRelayNode); put("proxy_type", proxyType.id); put("proxy_ip", proxyIp); put("proxy_port", proxyPort)
        cachedUpdateJson?.let { put("app_update_cached_json", it) }; put("app_update_ignored_version_code", ignoredVersionCode); put("download_count_limit", downloadCountLimit); put("download_speed_limit", downloadSpeedLimitIndex); put("download_segments", downloadSegments); put("use_private_storage", usePrivateStorage); safDownloadPath?.let { put("saf_download_path", it) }; put("collapse_downloaded_group", collapseDownloadedGroup)
        put("switch_player_kernel", playerKernel.value); put("enable_google_cast", enableGoogleCast); put("show_bottom_progress", showBottomProgress); put("player_speed", playerSpeed.toString()); put("slide_sensitivity", slideSensitivity); put("long_press_speed_times", longPressSpeedTime.toString()); put("video_language", videoLanguage); put("default_video_quality", videoQuality); put("show_played_indicator", showPlayedIndicator); put("allow_resume_playback", allowResumePlayback)
        put("when_countdown_remind", whenCountdownRemindSeconds); put("show_comment_when_countdown", showCommentWhenCountdown); put("h_keyframes_enable", hKeyframesEnable); put("shared_h_keyframes_enable", sharedHKeyframesEnable); put("shared_h_keyframes_use_first", sharedHKeyframesUseFirst)
        put("mpv_profile", mpvProfile); put("mpv_gpu_next_render", enableGpuNextRenderer); put("mpv_interpolation", mpvInterpolation); put("mpv_deband", mpvDeband); put("mpv_framedrop", mpvFramedrop); put("mpv_hwdecx", mpvHwdec); put("mpv_cache_secs", mpvCacheSecs); put("mpv_tls_verify", mpvTlsVerify); put("mpv_network_timeout", mpvNetworkTimeout); put("mpv_custom_parameters", customMpvParams)
        put("search_artist_ignore_video_type", searchArtistIgnoreVideoType); put("disable_mobile_data_warning", disableMobileDataWarning); put("fun_loading_hints", funLoadingHints); put("check_in_enabled", checkInEnabled)
        put("search_grid_columns_compact", searchGridColumnsCompact); put("search_grid_columns_medium", searchGridColumnsMedium); put("search_grid_columns_expanded", searchGridColumnsExpanded); put("search_grid_columns_large", searchGridColumnsLarge)
        put("horizontal_card_count_narrow", horizontalCardCountNarrow.toString()); put("horizontal_card_count_compact", horizontalCardCountCompact.toString()); put("horizontal_card_count_medium", horizontalCardCountMedium.toString()); put("horizontal_card_count_expanded", horizontalCardCountExpanded.toString())
        put("subscription_artist_rows", subscriptionArtistRows)
        put("home_category_order", homeCategoryOrder.joinToString(",")); put("home_category_hidden", hiddenHomeCategoryKeys.joinToString(","))
        put("developer_always_show_update_card", alwaysShowUpdateCard); put("developer_display_density_percent", displayDensity.percent)
    }

    private fun Preferences.bool(name: String, default: Boolean) = runCatching { this[booleanPreferencesKey(name)] }.getOrNull() ?: default
    private fun Preferences.int(name: String, default: Int) = intOrNull(name) ?: default
    private fun Preferences.intOrNull(name: String) = runCatching { this[intPreferencesKey(name)] }.getOrNull() ?: runCatching { this[stringPreferencesKey(name)]?.toIntOrNull() }.getOrNull()
    private fun Preferences.intInRange(name: String, default: Int, range: IntRange) = intOrNull(name)?.takeIf { it in range } ?: default
    private fun Preferences.string(name: String, default: String) = nullableString(name) ?: default
    private fun Preferences.nullableString(name: String) = runCatching { this[stringPreferencesKey(name)] }.getOrNull()
    private fun Preferences.floatString(name: String, default: Float) = nullableString(name)?.toFloatOrNull() ?: default
    private fun MutablePreferences.putRaw(name: String, value: Any) { when (value) { is Boolean -> this[booleanPreferencesKey(name)] = value; is Int -> this[intPreferencesKey(name)] = value; is String -> this[stringPreferencesKey(name)] = value } }
    private val AUTH_KEYS = setOf("already_login", "saved_user_id", "cookie", "cf_cookie", "cf_cookie_host")
}
