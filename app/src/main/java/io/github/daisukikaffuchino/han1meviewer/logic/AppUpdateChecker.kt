package io.github.daisukikaffuchino.han1meviewer.logic

import android.util.Base64
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.UPSTREAM_GITHUB_REPO
import io.github.daisukikaffuchino.han1meviewer.upstreamReleasePageUrl
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.network.GitHubDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.decodeFromStringByBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
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

/**
 * 上游（[UPSTREAM_GITHUB_REPO]）的最新发布版本。
 *
 * ⚠️ **上游的包本应用装不上** —— 两个事实都是硬约束，别指望能「一键更新到上游」：
 *
 * 1. **签名不同**：实测上游发布包的签名证书 SHA-256 是
 *    `F2:8A:4E:14:…:CF:E7`，本 fork 的 mod 包是 `B3:DD:C8:6C:…:7A:63`。
 *    Android 对同包名（都是 `io.github.daisukikaffuchino.han1meviewer`）的覆盖安装
 *    强制校验签名，不一致会直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
 * 2. **版本号更低**：上游 26.3.2 的 `versionCode` 是 `260805`，而 mod 线已经到 `260925`，
 *    就算签名一致也会被判成降级（`INSTALL_FAILED_VERSION_DOWNGRADE`）。
 *
 * 所以上游版本只用于**告知**（「上游又出新版了，mod 该合了」），
 * 真正的更新走本仓库的 `update.json`（见 [AppUpdateCheckResult.updateInfo]）。
 */
data class UpstreamReleaseInfo(
    /** 上游 tag，形如 `26.3.2`。 */
    val version: String,
    /** 这一次检查里上游是否比「当前 mod 所基于的版本」更新。 */
    val isNewerThanInstalled: Boolean,
    /** GitHub 上这个 tag 的发布页。 */
    val releasePageUrl: String,
    /** 上游 Release 的正文（更新说明）。只有走 GitHub API 那条源才拿得到，可能为空。 */
    val changelog: String = "",
)

data class AppUpdateCheckResult(
    /** 本仓库（mod 线）有可安装的新版本时非空。 */
    val updateInfo: AppUpdateInfo? = null,
    val announcement: Announcement? = null,
    /** 上游最新版本；读不到就是 null。 */
    val upstream: UpstreamReleaseInfo? = null,
    /** 上游没读到时，失败原因（用于在界面上如实说明，而不是假装「已是最新」）。 */
    val upstreamError: String? = null,
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

/** jsDelivr 的包版本列表（`data.jsdelivr.com/v1/packages/gh/<owner>/<repo>`）。 */
@Serializable
private data class JsDelivrPackage(
    val versions: List<JsDelivrVersion> = emptyList(),
)

@Serializable
private data class JsDelivrVersion(val version: String = "")

/** GitHub Releases API 的 `releases/latest` 响应里我们关心的字段。 */
@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("prerelease") val prerelease: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"

    /**
     * **本仓库**（mod 线）的更新信息源，沿用原实现的 base64 写法，指向仓库根目录的
     * `update.json`：
     *
     *     https://raw.githubusercontent.com/ddsmie4t2g/HanimeViewer/mod/update.json
     *
     * 发新版时只需要改这个 json 里的 versionName / versionCode / downloadUrl 即可。
     * 字段结构与 [AppUpdatePayload] 完全一致，解析逻辑无需改动。
     *
     * 注意分支是 `mod`：本仓库是 fork，`main` 上是另一条 0.19.x 线，
     * 这条 26.3.2-mod.x 线放在 `mod` 分支上，所以两个 URL 都锁 `mod`。
     *
     * ⚠️ **只有这份 json 带着 `versionCode`**，所以它是唯一能做「是否更新」判断、
     * 也是唯一能应用内下载安装的源。上游那条（[requestUpstreamLatest]）只能给出 tag。
     *
     * 存两份、按顺序回退：`raw.githubusercontent.com` 在部分网络下直连不通，
     * 先走 jsDelivr 这个 GitHub 加速 CDN，失败再退回 raw。
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

    /**
     * 当前版本所基于的「上游版本」三段数字。
     *
     * mod 线的 `versionName` 形如 `26.3.2-mod.7.0`，其中 `26.3.2` 就是它基于的上游版本；
     * 上游的 tag 则是裸的 `26.3.2`。两边取到的都是 `[26, 3, 2]`，
     * 于是「上游有没有出更新的版本」就退化成**同一个基准上的比较** ——
     * 不会因为 mod 后缀（`-mod.7.0`）而误判。
     */
    private val localBaseVersion: List<Int>?
        get() = baseVersionParts(BuildConfig.VERSION_NAME)

    /**
     * 本构建**所基于的上游版本**（形如 `26.3.2`），取不到就是 null。
     *
     * 界面用它把关系说清楚：「上游最新 26.3.2 / 本构建基于上游 26.3.2」——
     * 否则用户看到「上游有新版本」却找不到更新按钮，会以为功能坏了。
     */
    val installedBaseVersion: String?
        get() = VERSION_TRIPLE.find(BuildConfig.VERSION_NAME)?.value

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
     * ⚠️ 同时必须挂 [GitHubDns]：`raw.githubusercontent.com` / `api.github.com` 常被 DNS 投毒，
     * 系统解析出来的 IP 直接连不上；线程池里的解析结果全是死 IP 时，
     * 回退源等于形同虚设。见 [GitHubDns] 的说明。
     * （`data.jsdelivr.com` 不在内置表里，会正常走系统 DNS。）
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
            .proxyAuthenticator(HProxyAuthenticator.http)
            .build()
    }

    /**
     * 查一次上游版本用的 client。
     *
     * 单独一个是为了**不挂 [GitHubDns]**：主源是 `data.jsdelivr.com`，
     * 它跟 GitHub 完全不是一个 CDN，套上 GitHub 的钉 IP 表反而会把它打歪。
     */
    private val upstreamClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .build()
    }

    /**
     * 检查更新。
     *
     * 一次检查会同时问两个地方，因为它们的用途不同：
     * - **本仓库的 `update.json`** → [AppUpdateCheckResult.updateInfo]，能装的那个；
     * - **上游的 Release** → [AppUpdateCheckResult.upstream]，只用于告知（装不上，见
     *   [UpstreamReleaseInfo] 的说明）。
     *
     * 两边互相独立：一个挂了不影响另一个。
     */
    suspend fun checkForUpdate(): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        coroutineScope {
            // 两条源**并发**跑，而不是串行。
            //
            // 上游那条纯粹是「告知」（它的包本应用装不上，见 [UpstreamReleaseInfo]），
            // 串行的话，只要上游那条源慢（connect 10 s + read 15 s），
            // 首页那张可安装的更新卡片就要跟着一起等 —— 用户看到的是「检查更新很慢」，
            // 而慢的是那条根本不重要的支线。并发之后整体耗时 = max(两条源)，而不是相加。
            val modDeferred = async { fetchModResult() }
            val upstreamDeferred = async { runCatching { requestUpstreamLatest() } }

            val modResult = modDeferred.await()
            val upstreamOutcome = upstreamDeferred.await()

            var upstream: UpstreamReleaseInfo? = null
            var upstreamError: String? = null
            upstreamOutcome
                .onSuccess { fetched ->
                    if (fetched != null) {
                        upstream = fetched
                        LogUtil.d(
                            TAG,
                            "上游最新版本 ${fetched.version}（比当前基准新=${fetched.isNewerThanInstalled}）"
                        )
                    }
                }
                .onFailure {
                    upstreamError = it.message ?: it.javaClass.simpleName
                    LogUtil.e(TAG, "查上游版本失败", it)
                }

            AppUpdateCheckResult(
                updateInfo = modResult.updateInfo,
                announcement = modResult.announcement,
                upstream = upstream,
                upstreamError = upstreamError,
            )
        }
    }

    /**
     * 读本仓库的 `update.json`（读不到就退回上次缓存），只负责回答
     * 「本构建有没有可安装的新版本」。
     */
    private suspend fun fetchModResult(): AppUpdateCheckResult {
        val cachedJson = SettingsRepository.current.cachedUpdateJson
        val responseJson = runCatching { requestUpdateJson() }
            .onFailure { LogUtil.e(TAG, "检查本仓库更新失败", it) }
            .getOrNull()

        if (responseJson != null) SettingsRepository.setCachedUpdateJson(responseJson)

        val jsonToUse = responseJson ?: cachedJson
        if (responseJson == null) {
            jsonToUse?.let { LogUtil.d(TAG, "复用上次缓存的 update.json") }
        }
        return jsonToUse.toUpdateCheckResult()
    }

    suspend fun ignoreUpdate(versionCode: Int) = SettingsRepository.setIgnoredVersionCode(versionCode)

    //<editor-fold desc="本仓库 update.json">

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
                LogUtil.d(TAG, "本仓库更新响应（$url）：$json")
                return json
            }
            lastError = result.exceptionOrNull()
            LogUtil.e(TAG, "更新源失败：$url", lastError)
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

    //</editor-fold>

    //<editor-fold desc="上游版本">

    /**
     * 上游 [UPSTREAM_GITHUB_REPO] 的最新发布版本。
     *
     * ## 为什么要换源
     *
     * 原来「检查更新」只认仓库里的 `update.json`，而上游的版本信息**只能**从 GitHub 拿
     * （`api.github.com` / `github.com`）。实测在**不加代理的国内网络**下这两个域名
     * 是直接不通的（`000`），于是「检查更新」永远拿不到上游新版本 ——
     * 这不是逻辑错，是这条路根本走不出去。
     *
     * 而 `data.jsdelivr.com` 是同一条链路上**唯一实测可直连**的（`200`）。
     * jsDelivr 本来就是 GitHub 的内容 CDN，它的包信息接口会按语义化版本倒序列出
     * 仓库的全部 tag，正好就是我们要的「上游最新版本」。
     *
     * 拿到 tag 就能拼出发布页；`tag → 26.4.0` 这种直接可读，也不需要额外维护一份 json。
     *
     * 顺序：jsDelivr（可直连）→ GitHub API（需要代理/DNS 兜底，但能多拿到更新说明）。
     */
    private suspend fun requestUpstreamLatest(): UpstreamReleaseInfo? {
        var lastError: Throwable? = null

        runCatching { requestUpstreamFromJsDelivr() }
            .onSuccess { if (it != null) return it }
            .onFailure {
                lastError = it
                LogUtil.w(TAG, "jsDelivr 查上游版本失败，回退 GitHub API：${it.message}")
            }

        runCatching { requestUpstreamFromGitHubApi() }
            .onSuccess { if (it != null) return it }
            .onFailure {
                lastError = it
                LogUtil.w(TAG, "GitHub API 查上游版本也失败：${it.message}")
            }

        // 两个源都失败时，把原因抛出去（调用方会放进 upstreamError，界面上如实显示）
        lastError?.let { throw it }
        return null
    }

    private fun requestUpstreamFromJsDelivr(): UpstreamReleaseInfo? {
        val url = "https://data.jsdelivr.com/v1/packages/gh/$UPSTREAM_GITHUB_REPO"
        val body = upstreamClient.newCall(Request.Builder().url(url).get().build())
            .execute().use { response ->
                check(response.isSuccessful) { "jsDelivr HTTP ${response.code}" }
                response.body.string()
            }
        val pkg = jsonParser.decodeFromString<JsDelivrPackage>(body)
        val tag = pkg.versions
            .map { it.version.trim() }
            .filter { baseVersionParts(it) != null }
            .maxWithOrNull { a, b -> compareBaseVersions(baseVersionParts(a)!!, baseVersionParts(b)!!) }
            ?: throw IllegalStateException("jsDelivr 里没有可识别的版本 tag")
        LogUtil.d(TAG, "jsDelivr 上游最新 tag：$tag")
        return tag.toUpstreamReleaseInfo(changelog = "")
    }

    private fun requestUpstreamFromGitHubApi(): UpstreamReleaseInfo? {
        val url = "https://api.github.com/repos/$UPSTREAM_GITHUB_REPO/releases/latest"
        val body = client.newCall(Request.Builder().url(url).get().build())
            .execute().use { response ->
                check(response.isSuccessful) { "GitHub API HTTP ${response.code}" }
                response.body.string()
            }
        val release = jsonParser.decodeFromString<GitHubRelease>(body)
        val tag = release.tagName.trim()
        if (tag.isBlank()) throw IllegalStateException("releases/latest 里没有 tag_name")
        LogUtil.d(TAG, "GitHub API 上游最新 tag：$tag")
        return tag.toUpstreamReleaseInfo(changelog = release.body.trim())
    }

    private fun String.toUpstreamReleaseInfo(changelog: String): UpstreamReleaseInfo {
        val upstreamParts = baseVersionParts(this)
        val localParts = localBaseVersion
        return UpstreamReleaseInfo(
            version = this,
            isNewerThanInstalled = upstreamParts != null && localParts != null &&
                compareBaseVersions(upstreamParts, localParts) > 0,
            releasePageUrl = upstreamReleasePageUrl(this),
            changelog = changelog,
        )
    }

    //</editor-fold>

    //<editor-fold desc="版本号工具">

    /**
     * 从 `26.3.2-mod.7.0` / `26.3.2` 里抠出前三段数字 `[26, 3, 2]`。
     *
     * 只认「三段数字」这一种形式：上游的预览 tag（`26.3.0p`）也有 `26.3.0`，
     * 会被当成 26.3.0 参与比较 —— 即使如此也只会算出「不比 26.3.2 新」，不会误报。
     */
    private fun baseVersionParts(raw: String): List<Int>? {
        val match = VERSION_TRIPLE.find(raw) ?: return null
        val parts = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        return parts.takeIf { it.size == 3 }
    }

    /** 三段数字的字典序比较。 */
    private fun compareBaseVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }

    /** ⚠️ 正则里**不能**出现裸 `{` / `}` —— Android 的 ICU 引擎会判它语法错误，见工程约定。 */
    private val VERSION_TRIPLE = Regex("""(\d+)\.(\d+)\.(\d+)""")

    //</editor-fold>
}
