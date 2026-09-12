package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.USER_AGENT
import io.github.daisukikaffuchino.han1meviewer.logic.network.GitHubDns
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
import java.io.FileOutputStream
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
/**
 * 更新包下载进度。
 *
 * 为什么**一定要带上字节数**：`percent` 只有在服务端给出 `Content-Length` 时才算得出来，
 * 走分块传输（或某些加速镜像）时永远是 null。那时如果只上报百分比，界面就只剩一条
 * 不动的进度条 —— 用户无法区分「在下」和「卡死」，这正是「不知道在下不下」的来源。
 * [bytes] 哪怕没有百分比也一直在涨，是「真的在下」的硬证据。
 */
data class DownloadProgress(
    /** 0..100；算不出来时是 null（不知道总大小）。 */
    val percent: Int?,
    /** 已落盘字节数。 */
    val bytes: Long,
)

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
     *
     * 25 s → 60 s：对齐参考项目 `HanimeViewer999`（它的 `githubClient` 就是 60 s）。
     * 25 s 实测太紧 —— 本机到 GitHub 出口只有 30–40 KB/s，任何一次网络抖动
     * （尤其 302 到 `release-assets` 之后的那一跳）都可能超过 25 s 没有数据块到达，
     * 于是**明明能下完的源被误判成失败**，一路切到那几个已经死掉的镜像上，最终整体报错。
     */
    private const val READ_TIMEOUT_SECONDS = 60L

    /**
     * GitHub 加速镜像，**前缀式**：把完整的 GitHub 链接直接拼在后面即可。
     *
     * 这些是第三方公益加速服务，可用性变化很快。2026-09-11 在本机实测（出沙箱、真实网络）：
     *
     * | 前缀 | 结果 |
     * |---|---|
     * | `ghproxy.net` | 206，但只有 ~12 KB/s（比官方还慢，留作最后兜底） |
     * | `ghfast.top` | 000（完全不通） |
     * | `gitproxy.click` | 200 但只回 195 字节的错误页 |
     * | `gh-proxy.com` / `hub.gitmirror.com` / `gh.llkk.cc` / … | 000 |
     *
     * 所以**不要指望镜像**：官方源（配 [GitHubDns]）才是主力，镜像只是「聊胜于无」的最后一条。
     * 不要再往这里堆域名 —— 实测十几个公共镜像几乎全灭，堆它们只会让失败路径变得更长。
     *
     * 安全性：APK 最终要过 Android 的签名校验（同包名必须同签名），
     * 任何被篡改的包都装不上，所以走镜像不会带来「装上假包」的风险。
     */
    private val MIRROR_PREFIXES = listOf(
        "https://ghproxy.net/",
    )

    /**
     * 单个源的最大尝试次数。
     *
     * 同一个源**重试时保留半截文件、带 `Range` 从断点续传**。这一点很关键：
     * 本机到 GitHub 出口只有 30–40 KB/s，28 MB 的包要十几分钟，
     * 「断一次就从 0 重来」等于永远下不完。换源（不同源字节未必一致）时才清空重来。
     */
    private const val ATTEMPTS_PER_SOURCE = 2

    /**
     * 进度上报的最小间隔（毫秒）。
     *
     * 每收一块数据就回调一次太密：`AppUpdateWorker` 每次都会 `setProgress`（写 WorkManager
     * 的数据库）并 `NotificationManager.notify`，一秒几十次纯属浪费。500 ms 既能让进度条
     * 看起来是连续在走，又不会把主线程压满。
     */
    private const val PROGRESS_INTERVAL_MS = 500L

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
     *
     * ⚠️ 同时必须挂 [GitHubDns]：`github.com` 与 `release-assets.githubusercontent.com`
     * 被 DNS 投毒时，系统解析出的 IP 根本连不上 —— 这时连「开始下载」都做不到。
     * 这是「参考项目能更新、本应用更新不动」的真正区别所在。
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .dns(GitHubDns)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
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
     * @param onProgress 进度回调。**开工时（还没收到任何数据）就会先回调一次**
     *   `DownloadProgress(percent = null, bytes = 已有字节数)` —— 这是刻意为之：
     *   否则「服务端迟迟不给 `Content-Length`」和「请求根本没发出去」在界面上长得一模一样，
     *   用户看到的就是「点了更新之后什么都没发生，不知道在下不下」。
     *   `percent` 只有在服务端给出 `Content-Length` 时才算得出来，算不出来就是 null；
     *   此时界面靠 `bytes` 一直在涨来判断「真的在下」。
     * @return 下载完成的 APK 文件
     */
    suspend fun download(
        url: String,
        onProgress: (suspend (DownloadProgress) -> Unit)? = null,
    ): File =
        withContext(Dispatchers.IO) {
            val candidates = candidateUrls(url)
            var lastError: Throwable? = null

            candidates.forEachIndexed { index, candidate ->
                if (index > 0) {
                    // 换源：不同源的字节未必一致，半截文件不能续，清掉重来
                    runCatching { updateApkFile().delete() }
                    onProgress?.invoke(DownloadProgress(percent = null, bytes = 0L))
                }

                repeat(ATTEMPTS_PER_SOURCE) { attempt ->
                    val result = runCatching { downloadFrom(candidate, onProgress) }
                    result.getOrNull()?.let { file ->
                        LogUtil.d(
                            TAG,
                            "更新包下载完成（源 ${index + 1}/${candidates.size}，第 ${attempt + 1} 次尝试）：" +
                                "${file.absolutePath}（${file.length()} 字节）"
                        )
                        return@withContext file
                    }

                    lastError = result.exceptionOrNull()
                    LogUtil.w(
                        TAG,
                        "更新源 ${index + 1} 第 ${attempt + 1} 次失败：$candidate",
                        lastError
                    )
                }

                if (index < candidates.lastIndex) {
                    LogUtil.w(TAG, "更新源 ${index + 1} 放弃，回退下一个：$candidate", lastError)
                }
            }

            throw IOException("已尝试 ${candidates.size} 个更新源，均下载失败", lastError)
        }

    /** 从**单个**源下载（支持断点续传）。任何异常都向上抛，由 [download] 决定重试或换源。 */
    private suspend fun downloadFrom(
        url: String,
        onProgress: (suspend (DownloadProgress) -> Unit)?,
    ): File {
        val file = updateApkFile()
        file.parentFile?.mkdirs()

        // 断点续传：同源上一次下了一半就断了的话，从断点接着下（详见 ATTEMPTS_PER_SOURCE）
        val alreadyBytes = file.takeIf { it.isFile }?.length() ?: 0L

        // ⚠️ 开工先上报一次。此刻一个字节都还没到，但「已开始」这件事必须让界面知道 ——
        // 否则从点按钮到第一块数据到达之间（弱网下可能十几秒）界面毫无变化，
        // 用户根本分不清是在下还是卡死。
        onProgress?.invoke(DownloadProgress(percent = null, bytes = alreadyBytes))

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { if (alreadyBytes > 0L) header("Range", "bytes=$alreadyBytes-") }
            .get()
            .build()

        val totalBytes = client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                // 断点位置已越界（通常是上次其实已下满但校验没过），清空重来
                file.delete()
                throw IOException("续传位置越界（HTTP 416），已重置")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            val body = response.body
            val bodyLength = body.contentLength()
            // 206 = 服务端接受了 Range，可以接着写；200 = 不支持 Range，只能从头来
            val resuming = response.code == 206 && alreadyBytes > 0L
            val total = when {
                !resuming -> bodyLength
                bodyLength > 0 -> alreadyBytes + bodyLength
                else -> -1L
            }

            body.byteStream().use { input ->
                FileOutputStream(file, resuming).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = if (resuming) alreadyBytes else 0L
                    var lastEmitAt = 0L

                    /** 有总长才算得出百分比；算不出来就给 null，交给界面按「不确定」显示。 */
                    fun progressOf() = if (total > 0) {
                        DownloadProgress(
                            percent = ((copied * 100) / total).toInt().coerceIn(0, 100),
                            bytes = copied,
                        )
                    } else {
                        DownloadProgress(percent = null, bytes = copied)
                    }

                    // 拿到了响应头即刻再报一次：这时已经知道总大小了，能把「不确定进度条」
                    // 换成带百分比的确定进度条（哪怕字节数还是 0）。
                    onProgress?.invoke(progressOf())

                    while (true) {
                        // 用户取消下载时能及时退出，不会留下半截文件继续写
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitAt >= PROGRESS_INTERVAL_MS) {
                            lastEmitAt = now
                            onProgress?.invoke(progressOf())
                        }
                    }

                    // 收尾补一次终值，避免最后一截数据落在节流窗口里没上报
                    onProgress?.invoke(progressOf())
                }
            }
            total
        }

        // 只写 `body.use {}` 而不管响应码的话，4xx/5xx 会留下 0 字节文件却仍算「成功」——
        // 表现是通知卡在 0%、点安装报「解析包错误」。这里显式校验。
        val actualLength = file.length()
        if (actualLength <= 0L) {
            file.delete()
            throw IOException("更新包为空（0 字节）")
        }
        if (totalBytes > 0 && actualLength != totalBytes) {
            // 下少了（多半是中途断流）：**保留半截文件**，交给上层重试时续传，不要 delete
            throw IOException("更新包不完整：期望 $totalBytes 字节，实际 $actualLength 字节")
        }

        // APK 本质是 zip，文件头固定为 "PK\x03\x04"。有些「加速镜像」在失败时会返回
        // 一个**完整的** HTML 错误页 —— 长度校验会放过它，装的时候才报「解析包错误」。
        // 这里补一道魔数校验，把这种包挡在安装之前。
        val head = ByteArray(4)
        runCatching { file.inputStream().use { it.read(head) } }
        if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) {
            file.delete()
            throw IOException("更新包不是合法的 APK（文件头异常）")
        }

        return file
    }
}
