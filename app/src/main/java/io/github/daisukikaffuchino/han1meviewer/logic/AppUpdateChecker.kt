package io.github.daisukikaffuchino.han1meviewer.logic

import android.util.Base64
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
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
     *     https://raw.githubusercontent.com/Wanttosleep2005/HanimeViewer/mod/update.json
     *
     * 发新版时只需要改这个 json 里的 versionName / versionCode / downloadUrl 即可。
     * 字段结构与 [AppUpdatePayload] 完全一致，解析逻辑无需改动。
     *
     * 注意分支是 `mod`：本仓库是 fork，`main` 上是另一条 0.19.x 线，
     * 这条 26.3.2-mod.x 线放在 `mod` 分支上，所以两个 URL 都锁 `mod`。
     *
     * 存两份、按顺序回退：`raw.githubusercontent.com` 在部分网络下直连不通，
     * 先走 jsDelivr 这个 GitHub 加速 CDN，失败再退回 raw。
     */
    private val UPDATE_URLS = listOf(
        // jsDelivr（GitHub 内容加速，国内一般可直连）
        "aHR0cHM6Ly9jZG4uanNkZWxpdnIubmV0L2doL1dhbnR0b3NsZWVwMjAwNS9IYW5pbWVWaWV3ZXJAbW9kL3VwZGF0ZS5qc29u",
        // GitHub raw（直连，可能需要代理）
        "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL1dhbnR0b3NsZWVwMjAwNS9IYW5pbWVWaWV3ZXIvbW9kL3VwZGF0ZS5qc29u",
    )

    /** 原实现用于腾讯云 COS 防盗链；对 raw.githubusercontent 无影响，保留以免动到请求结构。 */
    private const val ENCODED_UPDATE_REFERER = "aG5tdmlld2VydXAuY29t"

    // 需与 app/build.gradle.kts 的 versionCode 保持一致
    private const val CURRENT_VERSION_CODE = 260912

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
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

        val currentVersionCode = CURRENT_VERSION_CODE
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
