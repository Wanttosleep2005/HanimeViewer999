package io.github.daisukikaffuchino.han1meviewer.logic

import android.util.Base64
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.network.GitHubDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.decodeFromStringByBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val updateDescription: String,
    val forceUpdate: Boolean,
)

data class AppUpdateCheckResult(
    val updateInfo: AppUpdateInfo? = null,
    val announcement: Announcement? = null,
)

sealed interface AppUpdateState {
    data object Checking : AppUpdateState
    data object NoUpdate : AppUpdateState
    data class Available(val info: AppUpdateInfo) : AppUpdateState
}

@Serializable
private data class AppUpdatePayload(
    val versionName: String? = null,
    val versionCode: Int = 0,
    val downloadUrl: String? = null,
    val updateDescription: String = "",
    val forceUpdate: Boolean = false,
    val isShowAnnouncement: Boolean = false,
    val announcement: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"

    /**
     * 更新信息源。原本指向上游作者的腾讯云 COS，这里改为**本仓库**根目录下的
     * `update.json`（沿用原实现的 base64 写法）：
     *
     *     https://raw.githubusercontent.com/ddsmie4t2g/HanimeViewer/mod/update.json
     *
     * 发新版时只需要改这个 json 里的 versionName / versionCode / downloadUrl 即可。
     * 字段结构与 [AppUpdatePayload] 完全一致，解析逻辑无需改动。
     *
     * 注意分支是 `mod`：本仓库是 fork，`main` 上是另一条 0.19.x 线，
     * 这条 26.3.2-mod.x 线放在 `mod` 分支上，所以两个 URL 都锁 `mod`。
     *
     * 存两份、按顺序回退：`raw.githubusercontent.com` 在部分网络下直连不通，
     * 先走 jsDelivr 这个 GitHub 加速 CDN，失败再退回 raw。
     *
     * ⚠️ 仓库所有者是 `ddsmie4t2g`：本仓库于 2026-09-11 从 `Wanttosleep2005`
     * 转移过来。URL 里的 owner 是**硬编码在 APK 里**的，换账号必须重新打包，
     * 否则旧包仍去请求旧地址。转移后 GitHub 会对旧地址做 301，所以已装出去的
     * 旧包靠 raw 那条回退链还能续上一段时间，但 jsDelivr 那条不保证。
     */
    private val UPDATE_URLS = listOf(
        // jsDelivr（GitHub 内容加速，国内一般可直连）
        "aHR0cHM6Ly9jZG4uanNkZWxpdnIubmV0L2doL2Rkc21pZTR0MmcvSGFuaW1lVmlld2VyQG1vZC91cGRhdGUuanNvbg==",
        // GitHub raw（直连，可能需要代理）
        "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL2Rkc21pZTR0MmcvSGFuaW1lVmlld2VyL21vZC91cGRhdGUuanNvbg==",
    )

    /** 原实现用于腾讯云 COS 防盗链；对 raw.githubusercontent 无影响，保留以免动到请求结构。 */
    private const val ENCODED_UPDATE_REFERER = "aG5tdmlld2VydXAuY29t"

    /**
     * 当前安装在设备上的版本号。
     *
     * ⚠️ **不要**再在这里写死一个常量。历史上这里写的是 `260914`，而
     * `app/build.gradle.kts` 的 `versionCode` 是 `260915`，两边一旦不同步，
     * `it.versionCode > currentVersionCode` 就永远不成立 —— 表现就是
     * **发版之后「检查更新」一直不推送，明明仓库里的 update.json 已经是最新的**。
     *
     * `BuildConfig.VERSION_CODE` 由 `build.gradle.kts` 的 `defaultConfig.versionCode`
     * 生成（见 `buildConfigField("int", "VERSION_CODE", ...)`），是同一份数据源，
     * 天然不会漂移。
     */
    private val currentVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * 检查更新用的 client。
     *
     * ⚠️ 必须挂 [HProxySelector]：更新源里的 `raw.githubusercontent.com` 在部分网络下
     * 直连不通（jsDelivr 那条一般能直连，所以「检查更新」看起来还能用），
     * 挂上代理后用户配的代理对两条源都生效，和 [AppUpdateDownloader] 保持一致。
     *
     * ⚠️ 同时必须挂 [GitHubDns]：`raw.githubusercontent.com` 常被 DNS 投毒，
     * 系统解析出来的 IP 直接连不上；线程池里的解析结果全是死 IP 时，
     * 回退源等于形同虚设。见 [GitHubDns] 的说明。
     *
     * `readTimeout` 从 15 s 放宽到 20 s：这个值约束的是「两次数据到达之间的最大间隔」，
     * 弱网下 15 s 太紧，会把「只是慢」误判成「失败」而白白切到更差的源。
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(GitHubDns)
            .proxySelector(HProxySelector())
            .build()
    }

    suspend fun checkForUpdate(): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val cachedJson = SettingsRepository.current.cachedUpdateJson

        val responseJson = runCatching { requestUpdateJson() }
            .onFailure { LogUtil.e(TAG, "Failed to check for updates", it) }
            .getOrNull()

        if (responseJson != null) SettingsRepository.setCachedUpdateJson(responseJson)

        val jsonToUse = responseJson ?: cachedJson
        if (responseJson == null) {
            jsonToUse?.let { LogUtil.d(TAG, "Using stale update JSON: $it") }
        }
        jsonToUse.toUpdateCheckResult()
    }

    suspend fun ignoreUpdate(versionCode: Int) = SettingsRepository.setIgnoredVersionCode(versionCode)

    private fun requestUpdateJson(): String {
        var lastError: Throwable? = null
        for (encoded in UPDATE_URLS) {
            val url = encoded.decodeFromStringByBase64(Base64.NO_WRAP)
            val request = Request.Builder()
                .url(url)
                .header(
                    "Referer",
                    ENCODED_UPDATE_REFERER.decodeFromStringByBase64(Base64.NO_WRAP)
                )
                .get()
                .build()
            val result = runCatching {
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Update check failed with HTTP ${response.code}" }
                    response.body.string()
                }
            }
            result.getOrNull()?.let { json ->
                LogUtil.d(TAG, "Update response JSON from $url: $json")
                return json
            }
            lastError = result.exceptionOrNull()
            LogUtil.e(TAG, "Update source failed: $url", lastError)
        }
        throw lastError ?: IllegalStateException("No update source configured")
    }

    private fun String?.toUpdateCheckResult(): AppUpdateCheckResult {
        if (this.isNullOrBlank()) return AppUpdateCheckResult()
        return runCatching {
            val payload = jsonParser.decodeFromString<AppUpdatePayload>(this)
            AppUpdateCheckResult(
                updateInfo = payload.toAvailableUpdateOrNull(),
                announcement = payload.toAnnouncementOrNull(),
            )
        }.onFailure {
            LogUtil.e(TAG, "Invalid update JSON", it)
        }.getOrDefault(AppUpdateCheckResult())
    }

    private fun AppUpdatePayload.toAvailableUpdateOrNull(): AppUpdateInfo? {
        val versionName = versionName?.trim().orEmpty()
        val downloadUrl = downloadUrl?.trim().orEmpty()
        if (versionName.isBlank() || versionCode <= 0 || downloadUrl.isBlank()) return null
        if (downloadUrl.toHttpUrlOrNull() == null) {
            LogUtil.e(TAG, "downloadUrl is invalid")
            return null
        }

        val ignoredVersionCode = SettingsRepository.current.ignoredVersionCode
        return AppUpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            downloadUrl = downloadUrl,
            updateDescription = updateDescription,
            forceUpdate = forceUpdate,
        ).takeIf {
            it.versionCode > currentVersionCode &&
                (it.forceUpdate || it.versionCode != ignoredVersionCode)
        }
    }

    private fun AppUpdatePayload.toAnnouncementOrNull(): Announcement? {
        val content = announcement.trim()
        if (!isShowAnnouncement || content.isBlank()) return null
        return Announcement(
            title = applicationContext.getString(R.string.update_announcement_title),
            content = content,
            isActive = true,
        )
    }
}
