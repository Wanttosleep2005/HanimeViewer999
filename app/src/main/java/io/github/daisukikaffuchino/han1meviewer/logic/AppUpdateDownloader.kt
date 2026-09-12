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
     * @param onProgress 0..100。服务端未给出 `Content-Length`（分块传输）时**不会**回调 ——
     *   此时换算不出百分比，宁可不动也不要谎报一个 0%。换源成功开始读取时会把进度重置为 0。
     * @return 下载完成的 APK 文件
     */
    suspend fun download(url: String, onProgress: (suspend (Int) -> Unit)? = null): File =
        withContext(Dispatchers.IO) {
            val candidates = candidateUrls(url)
            var lastError: Throwable? = null

            candidates.forEachIndexed { index, candidate ->
                if (index > 0) {
                    // 换源：不同源的字节未必一致，半截文件不能续，清掉重来
                    runCatching { updateApkFile().delete() }
                    onProgress?.invoke(0)
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
        onProgress: (suspend (Int) -> Unit)?,
    ): File {
        val file = updateApkFile()
        file.parentFile?.mkdirs()

        // 断点续传：同源上一次下了一半就断了的话，从断点接着下（详见 ATTEMPTS_PER_SOURCE）
        val alreadyBytes = file.takeIf { it.isFile }?.length() ?: 0L

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
                    var lastPercent = -1
                    while (true) {
                        // 用户取消下载时能及时退出，不会留下半截文件继续写
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress?.invoke(percent)
                            }
                        }
                    }
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
