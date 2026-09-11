package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.USER_AGENT
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.applicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用内更新包下载。
 *
 * 原先点「立即更新」走的是 `uriHandler.openUri(downloadUrl)` —— 把
 * `.../releases/download/vX/App.apk` 丢给浏览器，于是要跳出 App、在浏览器里等下载、
 * 再从浏览器的下载列表里点安装，体验很割裂。这里改成应用内下载，完成后直接拉起系统安装器。
 *
 * ---
 *
 * ## 为什么下载要依次试多个源
 *
 * `update.json` 里的 `downloadUrl` 指向 `github.com/<owner>/<repo>/releases/download/...`，
 * 这个链接会 **302 跳到 `release-assets.githubusercontent.com`**（附带一段一小时内有效的
 * 签名 URL）。国内网络下这个资产域名经常**直连不上或速率趋近于 0** ——
 * 表现就是更新卡片一直停在 0%、「下载不了」。
 *
 * 注意这和「检查更新」是两回事：`update.json` 走的是 jsDelivr（国内可直连），
 * 所以**能检测到新版本、却下不动安装包**。见 [AppUpdateChecker]。
 *
 * 对策有两层：
 * 1. 挂上 [HProxySelector]，让用户在应用里配的代理对更新流量同样生效
 *    （此前这里是裸 OkHttpClient，代理配置被完全忽略，见下）；
 * 2. 官方源失败就依次回退到 GitHub 加速镜像。
 */
object AppUpdateDownloader {

    private const val TAG = "AppUpdateDownloader"

    /** 下载落点。`cacheDir` 已被 `res/xml/file_paths.xml` 的 `<cache-path path="." />` 覆盖。 */
    private const val APK_NAME = "update.apk"

    /**
     * 单个源的「零字节」容忍时长。
     *
     * OkHttp 的 `readTimeout` 语义正是「两次数据到达之间的最大间隔」，所以这个值等于
     * **某个源卡住多久就判定它没救、换下一个**。注意它对「慢但一直在动」的源不生效
     * （那种情况至少进度条会走，不算「卡死」）。
     */
    private const val READ_TIMEOUT_SECONDS = 25L

    /**
     * GitHub 加速镜像，**前缀式**：把完整的 GitHub 链接直接拼在后面即可。
     *
     * 这些是第三方公益加速服务，可用性会变。所以：
     * - 官方源永远排在第一位，镜像只是回退；
     * - 全部失败时抛错，UI 会给出「用浏览器打开」的兜底入口。
     *
     * 安全性：APK 最终要过 Android 的签名校验（同包名必须同签名），
     * 任何被篡改的包都装不上，所以走镜像不会带来「装上假包」的风险。
     */
    private val MIRROR_PREFIXES = listOf(
        "https://ghproxy.net/",
        "https://gitproxy.click/",
        "https://ghfast.top/",
    )

    fun updateApkFile(): File = File(applicationContext.cacheDir, APK_NAME)

    /** 已下载好的更新包（存在且非空）才返回，否则 null。 */
    fun existingApkFileOrNull(): File? = updateApkFile().takeIf { it.isFile && it.length() > 0L }

    fun clearApkFile() {
        runCatching { updateApkFile().delete() }
    }

    /**
     * 专用 client。
     *
     * 不用 `ServiceCreator.downloadClient`：它挂着 `SpeedLimitInterceptor`，用户设过下载限速
     * 时会把 28 MB 的更新包拖到难以接受；而且它没配 `readTimeout`，走 OkHttp 默认的 10 秒，
     * 弱网下中途断流就前功尽弃。更新包走独立短连接，也别用 `hClient`（带 HTTP 缓存 + Cloudflare 拦截器）。
     *
     * ⚠️ **必须挂 [HProxySelector]**。它读的是 `SettingsRepository` 的代理设置，在每次
     * `select()` 时动态取值，所以用户改代理后不需要重建这个 client。
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .proxySelector(HProxySelector())
            .build()
    }

    /** 按顺序尝试的下载源：GitHub 官方 → 各加速镜像。 */
    private fun candidateUrls(url: String): List<String> = buildList {
        add(url)
        MIRROR_PREFIXES.forEach { prefix -> add(prefix + url) }
    }

    /**
     * 下载更新包到 [updateApkFile]，依次尝试官方源与各镜像，第一个成功即返回。
     *
     * @param onProgress 0..100。服务端未给出 `Content-Length`（分块传输）时**不会**回调 ——
     *   此时换算不出百分比，宁可不动也不要谎报一个 0%。换源成功开始读取时会把进度重置为 0。
     * @return 下载完成的 APK 文件
     */
    suspend fun download(url: String, onProgress: (suspend (Int) -> Unit)? = null): File =
        withContext(Dispatchers.IO) {
            val candidates = candidateUrls(url)
            var lastError: Throwable? = null

            candidates.forEachIndexed { index, candidate ->
                // 清掉上一轮留下的半截文件，否则会被当成本次结果
                runCatching { updateApkFile().delete() }
                // 换源时把进度打回 0，免得上一个源的百分比僵在那里误导用户
                if (index > 0) onProgress?.invoke(0)

                val result = runCatching { downloadFrom(candidate, onProgress) }
                result.getOrNull()?.let { file ->
                    LogUtil.d(
                        TAG,
                        "更新包下载完成（源 ${index + 1}/${candidates.size}）：${file.absolutePath}（${file.length()} 字节）"
                    )
                    return@withContext file
                }

                lastError = result.exceptionOrNull()
                if (index < candidates.lastIndex) {
                    LogUtil.w(TAG, "更新源 ${index + 1} 失败，回退下一个：$candidate", lastError)
                }
            }

            throw IOException("已尝试 ${candidates.size} 个更新源，均下载失败", lastError)
        }

    /** 从**单个**源下载。任何异常都向上抛，由 [download] 决定是否换源。 */
    private suspend fun downloadFrom(
        url: String,
        onProgress: (suspend (Int) -> Unit)?,
    ): File {
        val file = updateApkFile()
        file.parentFile?.mkdirs()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val expectedLength = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        // 用户取消下载时能及时退出，不会留下半截文件继续写
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (contentLength > 0) {
                            val percent = ((copied * 100) / contentLength)
                                .toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress?.invoke(percent)
                            }
                        }
                    }
                }
            }
            contentLength
        }

        // 只写 `body.use {}` 而不管响应码的话，4xx/5xx 会留下 0 字节文件却仍算「成功」——
        // 表现是通知卡在 0%、点安装报「解析包错误」。这里显式校验。
        val actualLength = file.length()
        if (actualLength <= 0L) {
            file.delete()
            throw IOException("更新包为空（0 字节）")
        }
        if (expectedLength > 0 && actualLength != expectedLength) {
            file.delete()
            throw IOException("更新包不完整：期望 $expectedLength 字节，实际 $actualLength 字节")
        }

        return file
    }
}
