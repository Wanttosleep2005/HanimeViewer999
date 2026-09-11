package io.github.daisukikaffuchino.han1meviewer.logic

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
 */
object AppUpdateDownloader {

    private const val TAG = "AppUpdateDownloader"

    /** 下载落点。`cacheDir` 已被 `res/xml/file_paths.xml` 的 `<cache-path path="." />` 覆盖。 */
    private const val APK_NAME = "update.apk"

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
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载更新包到 [updateApkFile]。
     *
     * @param onProgress 0..100。服务端未给出 `Content-Length`（分块传输）时**不会**回调 ——
     *   此时换算不出百分比，宁可不动也不要谎报一个 0%。
     * @return 下载完成的 APK 文件
     */
    suspend fun download(url: String, onProgress: (suspend (Int) -> Unit)? = null): File =
        withContext(Dispatchers.IO) {
            val file = updateApkFile()
            // 先删旧的：否则上一次下了一半、或上一个版本的包会被当成本次结果
            if (file.exists() && !file.delete()) {
                throw IOException("无法删除旧的更新包：${file.absolutePath}")
            }
            file.parentFile?.mkdirs()

            val request = Request.Builder().url(url).get().build()
            val expectedLength = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("更新包下载失败：HTTP ${response.code} ($url)")
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
                throw IOException("更新包为空（0 字节）：$url")
            }
            if (expectedLength > 0 && actualLength != expectedLength) {
                file.delete()
                throw IOException("更新包不完整：期望 $expectedLength 字节，实际 $actualLength 字节")
            }

            LogUtil.d(TAG, "更新包下载完成：${file.absolutePath}（$actualLength 字节）")
            file
        }
}
