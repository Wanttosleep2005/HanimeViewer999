package io.github.daisukikaffuchino.han1meviewer.worker

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import io.github.daisukikaffuchino.utils.LogUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.daisukikaffuchino.han1meviewer.DOWNLOAD_NOTIFICATION_CHANNEL
import io.github.daisukikaffuchino.han1meviewer.EMPTY_STRING
import io.github.daisukikaffuchino.han1meviewer.HFileManager
import io.github.daisukikaffuchino.han1meviewer.HFileManager.createVideoName
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo
import io.github.daisukikaffuchino.han1meviewer.logic.entity.download.DownloadGroupEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.download.HanimeDownloadEntity
import io.github.daisukikaffuchino.han1meviewer.logic.hls.HlsPlaylist
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.ServiceCreator
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.state.DownloadState
import io.github.daisukikaffuchino.han1meviewer.util.HImageMeower
import io.github.daisukikaffuchino.han1meviewer.util.SafFileManager
import io.github.daisukikaffuchino.han1meviewer.util.await
import io.github.daisukikaffuchino.utils.createFileIfNotExists
import io.github.daisukikaffuchino.utils.saveTo
import io.github.daisukikaffuchino.utils.SonnerToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.closeQuietly
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.SocketException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2022/08/06 006 11:42
 */
class HanimeDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams), WorkerMixin {

    data class Args(
        val quality: String?,
        val downloadUrl: String?,
        val videoType: String?,
        val hanimeName: String,
        val videoCode: String,
        val coverUrl: String,
        val groupId: Int = DownloadGroupEntity.DEFAULT_GROUP_ID,
    ) {
        companion object {
            fun fromEntity(entity: HanimeDownloadEntity): Args {
                return Args(
                    quality = entity.quality,
                    downloadUrl = entity.videoUrl,
                    videoType = entity.suffix,
                    hanimeName = entity.title,
                    videoCode = entity.videoCode,
                    coverUrl = entity.coverUrl,
                    groupId = entity.groupId,
                )
            }
        }
    }

    companion object {
        const val TAG = "HanimeDownloadWorker"

        const val RESPONSE_INTERVAL = 500L

        const val BACKOFF_DELAY = 10_000L

        private const val MAX_STREAM_RETRY_COUNT = 3
        private const val MAX_WORK_RETRY_COUNT = 3

        /** HLS：估算总大小时抽样多少个分片（分片大小近乎等长，抽样足够准）。 */
        private const val HLS_SAMPLE_COUNT = 12

        /** HLS：单个分片最多重试几次。 */
        private const val HLS_SEGMENT_RETRY = 3

        /** HLS：断点续传索引文件后缀，内容是一行行「下一个分片序号,已写入字节数」。 */
        private const val HLS_INDEX_SUFFIX = ".hlsidx"

        const val FAST_PATH_CANCEL = "fast_path_cancel"
        const val DELETE = "delete"
        const val QUALITY = "quality"
        const val DOWNLOAD_URL = "download_url"
        const val VIDEO_TYPE = "video_type"
        const val HANIME_NAME = "hanime_name"
        const val VIDEO_CODE = "video_code"
        const val COVER_URL = "cover_url"
        const val GROUP_ID = "group_id"
        const val REDOWNLOAD = "redownload"
        const val IN_WAITING_QUEUE = "in_waiting_queue"
        // const val RELEASE_DATE = "release_date"
        // const val COVER_DOWNLOAD = "cover_download"

        const val PROGRESS = "progress"
        // const val FAILED_REASON = "failed_reason"

        private val CONTENT_RANGE_LENGTH_REGEX = Regex("/([0-9]+)$")

        /**
         * 方便统一管理下载 Worker 的创建
         */
        inline fun build(
            constraintsRequired: Boolean = true,
            action: OneTimeWorkRequest.Builder.() -> Unit = {}
        ): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()
            return OneTimeWorkRequestBuilder<HanimeDownloadWorker>()
                .addTag(TAG)
                .let { builder ->
                    if (constraintsRequired) {
                        builder.setConstraints(constraints)
                    } else {
                        builder
                    }
                }.setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    BACKOFF_DELAY, TimeUnit.MILLISECONDS
                ).apply(action).build()
        }

        fun getRunningWorkInfoCount(context: Context): Flow<Int> {
            return WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(TAG)
                .map { workInfos ->
                    workInfos.count {
                        it.state == WorkInfo.State.RUNNING
                    }
                }.distinctUntilChanged()
        }
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    private val hanimeName by inputData(HANIME_NAME, EMPTY_STRING)
    private val downloadUrl by inputData(DOWNLOAD_URL, EMPTY_STRING)
    private val videoType by inputData(VIDEO_TYPE, HFileManager.DEF_VIDEO_TYPE)
    private val quality by inputData(QUALITY, EMPTY_STRING)
    private val videoCode by inputData(VIDEO_CODE, EMPTY_STRING)
    private val coverUrl by inputData(COVER_URL, EMPTY_STRING)
    private val groupId by inputData(GROUP_ID, DownloadGroupEntity.DEFAULT_GROUP_ID)

    private val fastPathCancel by inputData(FAST_PATH_CANCEL, false)
    private val shouldDelete by inputData(DELETE, false)
    private val shouldRedownload by inputData(REDOWNLOAD, false)
    private val isInWaitingQueue by inputData(IN_WAITING_QUEUE, false)

    private val downloadId = Random.nextInt()

    /**
     * 是不是 HLS（`.m3u8`）源。
     *
     * nJAV 系站点给的就是 HLS 清单，**不能用「字节区间 + 单文件长度」那套下载**：
     * 直接拉 `.m3u8` 只会得到一个几 KB 的文本，所以以前每次都在
     * `fetchContentLength()` 那里拿到 null，界面报「无法获取文件大小或下载信息」。
     */
    private val isHlsDownload: Boolean get() = HlsPlaylist.isPlaylistUrl(downloadUrl)

    /**
     * 下载这些源时要带的请求头。
     *
     * 目前只有 `surrit.com` 需要（不带 `Referer: https://njavtv.com/` 一律 403）。
     * 对 hanime 的直链它返回空表，所以老路径一行行为都没变。
     * **分片请求也必须带** —— 防盗链是按域名判的，不是按主清单判的。
     */
    private val downloadHeaders: Map<String, String> get() = NjavNetwork.playbackHeadersFor(downloadUrl)

    /**
     * HLS 专用 client：沿用下载 client（保留限速拦截器 / UA / DNS），
     * 但把超时放宽 —— `downloadClient` 的 connect 5s 对这种一跳一跳的分片请求太紧。
     */
    private val hlsClient by lazy {
        ServiceCreator.downloadClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .proxySelector(HProxySelector())
            .build()
    }

    /**
     * 解析好的分片清单。
     *
     * [fetchContentLength] 与真正的下载都会用到它，缓存一份避免解析两遍
     * （解析本身要发 1~2 次请求，不是纯计算）。
     */
    @Volatile
    private var cachedHlsMedia: HlsPlaylist.Media? = null

    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)
    private val dbScope = CoroutineScope(Dispatchers.IO)

    override suspend fun doWork(): Result {
        if (fastPathCancel) return Result.success()
        setForeground(createForegroundInfo())
        return download()
    }

    private suspend fun createNewRaf(file: File): HanimeDownloadEntity? {
        return withContext(Dispatchers.IO) {
            var raf: RandomAccessFile? = null
            try {
                // SAF 优先
                val safUri = SafFileManager.getDownloadVideoFileUri(context, videoCode, createVideoName(hanimeName, quality, videoType))
                LogUtil.i(TAG,safUri.toString())
                if (safUri != null) {
                    context.contentResolver.openFileDescriptor(safUri, "rw")?.closeQuietly()
                } else {
                    file.createFileIfNotExists()
                    raf = RandomAccessFile(file, "rwd")
                }

                val len = fetchContentLength() ?: return@withContext null
                if (len > 0) {
                    // 创建数据库记录
                    val entity = HanimeDownloadEntity(
                        groupId = groupId,
                        coverUrl = coverUrl,
                        coverUri = null,
                        title = hanimeName,
                        addDate = System.currentTimeMillis(),
                        videoCode = videoCode,
                        videoUri = safUri?.toString() ?: file.toUri().toString(),
                        quality = quality,
                        videoUrl = downloadUrl,
                        length = len,
                        downloadedLength = 0,
                        state = DownloadState.Queued
                    )
                    DatabaseRepo.HanimeDownload.insert(entity)
                    // 预写入长度（只有 File 支持）。
                    // ⚠️ HLS 的 len 是**抽样估算**出来的，提前 setLength 会把文件撑成
                    // 一段空洞，之后按追加写就全错位了 —— 这种情况让文件从 0 自然长起来。
                    if (!isHlsDownload) raf?.setLength(len)
                    return@withContext entity
                }
            } catch (e: Exception) {
                if (e is CancellationException || e.isStoppedCancellation() || e.isRetryableNetworkError()) {
                    throw e
                }
                e.printStackTrace()
                if (file.exists() && file.length() == 0L) {
                    dbScope.launch {
                        HFileManager.getDownloadVideoFolder(context, videoCode).deleteRecursively()
                    }
                }
            } finally {
                raf?.closeQuietly()
            }
            null
        }
    }

    private suspend fun fetchContentLength(): Long? {
        // HLS 没有「一个文件的总长度」这种东西：得先解析清单，再抽样估算所有分片之和。
        // 见 [estimateHlsLength]。
        if (isHlsDownload) return estimateHlsLength(resolveHlsMedia())
        requestContentLength(useHead = true)?.let { return it }
        return requestContentLength(useHead = false)
    }

    private suspend fun requestContentLength(useHead: Boolean): Long? {
        val requestBuilder = Request.Builder().url(downloadUrl)
            .apply { downloadHeaders.forEach { (name, value) -> header(name, value) } }
        val request = if (useHead) {
            requestBuilder.head().build()
        } else {
            requestBuilder.header("Range", "bytes=0-0").get().build()
        }
        return try {
            ServiceCreator.downloadClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) return@use null
                if (useHead) {
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                        ?: response.contentLengthFromContentRange()
                } else {
                    response.contentLengthFromContentRange()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isRetryableNetworkError()) throw e
            null
        }
    }

    private fun Response.contentLengthFromContentRange(): Long? {
        return header("Content-Range")
            ?.let { CONTENT_RANGE_LENGTH_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
    }

    private suspend fun download(): Result {
        return withContext(Dispatchers.IO) {
            val file = HFileManager.getDownloadVideoFile(
                context = context, title = hanimeName, quality = quality, suffix = videoType, videoCode = videoCode
            )
            val safUri = SafFileManager.getDownloadVideoFileUri(context, videoCode, createVideoName(hanimeName, quality, videoType))
            // 检查是否需要重下载
            if (shouldRedownload || shouldDelete) {
                HFileManager.getDownloadVideoFolder(context, videoCode).deleteRecursively()
                DatabaseRepo.HanimeDownload.delete(videoCode)
                if (shouldDelete) {
                    return@withContext Result.success()
                }
            }
            var entity = try {
                DatabaseRepo.HanimeDownload.find(videoCode, quality) ?: run {
                    createNewRaf(file)
                    DatabaseRepo.HanimeDownload.find(videoCode, quality)
                        ?: return@withContext run {
                            LogUtil.d(TAG, "entity is null, create new raf failed")
                            val reason = context.getString(R.string.download_error_file_info)
                            showFailureNotification(reason)
                            mainScope.launch {
                                SonnerToast.error(
                                    context.getString(R.string.download_task_failed_s_reason_s, hanimeName, reason)
                                )
                            }
                            Result.failure(workDataOf(DownloadState.STATE to DownloadState.Failed.mask))
                        }
                }
            } catch (e: Exception) {
                if (e.isRetryableNetworkError() && runAttemptCount < MAX_WORK_RETRY_COUNT) {
                    DatabaseRepo.HanimeDownload.find(videoCode, quality)?.let {
                        DatabaseRepo.HanimeDownload.update(it.copy(state = DownloadState.Queued))
                    }
                    return@withContext Result.retry()
                }
                throw e
            }

            // HLS 的 length 是估算值，不能用它做「已完成 / 数据异常」的判断，
            // 否则估算偏小就会把没下完的任务标成完成、估算偏大又会把进度清零。
            // HLS 走 [downloadHls]，那里面按「分片是否全部写完」判定完成。
            if (!isHlsDownload && entity.downloadedLength >= entity.length && entity.length > 0) {
                DatabaseRepo.HanimeDownload.update(entity.copy(state = DownloadState.Finished))
                showSuccessNotification()
                return@withContext Result.success(
                    workDataOf(DownloadState.STATE to DownloadState.Finished.mask)
                )
            }

            if (!isHlsDownload && (entity.downloadedLength < 0 || entity.downloadedLength > entity.length)) {
                entity = entity.copy(downloadedLength = 0, state = DownloadState.Queued)
                DatabaseRepo.HanimeDownload.update(entity)
            }

            if (entity.coverUri == null) {
                updateCoverImage(entity)
            }
            if (isInWaitingQueue) {
                DatabaseRepo.HanimeDownload.update(
                    entity.copy(state = DownloadState.Queued)
                )
                return@withContext Result.success()
            }

            // HLS 是另一条路：没有「单文件 + 字节区间」这回事，必须按分片下。
            if (isHlsDownload) {
                return@withContext downloadHls(entity)
            }

            var downloadedLength = entity.downloadedLength
            val needRange = downloadedLength > 0
            var raf: RandomAccessFile? = null
            var safPfd: ParcelFileDescriptor? = null
            var safChannel: FileChannel? = null
            var response: Response? = null
            var body: ResponseBody? = null
            var bodyStream: InputStream? = null

            var result: Result = Result.failure(
                workDataOf(DownloadState.STATE to DownloadState.Failed.mask)
            )
            var shouldRetry = false

            try {
                if (safUri != null) {
                    safPfd = context.contentResolver.openFileDescriptor(safUri, "rw")
                    safChannel = safPfd?.fileDescriptor?.let { FileOutputStream(it).channel }
                        ?: throw IOException("Open SAF file failed")
                    if (downloadedLength > safChannel.size()) {
                        downloadedLength = safChannel.size()
                    }
                    safChannel.position(downloadedLength)
                } else {
                    raf = RandomAccessFile(file, "rwd")
                    if (needRange) raf.seek(downloadedLength)
                }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var delayTime = 0L
                var retryCount = 0

                while (downloadedLength < entity.length) {
                    val requestNeedRange = downloadedLength > 0
                    val requestBuilder = Request.Builder().url(downloadUrl).get()
                    if (requestNeedRange) requestBuilder.header("Range", "bytes=$downloadedLength-")
                    val request = requestBuilder.build()
                    response = ServiceCreator.downloadClient.newCall(request).await()
                    val canWrite = (requestNeedRange && response.code == 206) || (!requestNeedRange && response.isSuccessful)
                    if (!canWrite) {
                        val reason = response.toDownloadErrorMessage(requestNeedRange)
                        showFailureNotification(reason)
                        mainScope.launch {
                            SonnerToast.error(context.getString(R.string.download_task_failed_s_reason_s, hanimeName, reason))
                        }
                        result = Result.failure(workDataOf(DownloadState.STATE to DownloadState.Failed.mask))
                        return@withContext result
                    }

                    body = response.body
                    val responseBody = body
                    bodyStream = responseBody.byteStream()
                    var len: Int = bodyStream.read(buffer)

                    try {
                        while (len != -1) {
                            if (raf != null) {
                                raf.write(buffer, 0, len)
                            } else if (safChannel != null) {
                                safChannel.writeFully(buffer, len)
                            }
                            downloadedLength += len

                            if (System.currentTimeMillis() - delayTime > RESPONSE_INTERVAL) {
                                val progress = (downloadedLength * 100 / entity.length).coerceAtMost(100)
                                setProgress(workDataOf(PROGRESS to progress.toInt()))
                                updateDownloadNotification(progress.toInt())
                                DatabaseRepo.HanimeDownload.update(
                                    entity.copy(downloadedLength = downloadedLength,
                                        state = DownloadState.Downloading
                                    )
                                )
                                delayTime = System.currentTimeMillis()
                            }
                            len = bodyStream.read(buffer)
                        }
                    } catch (e: IOException) {
                        if (!e.isStreamResetCancel() || retryCount >= MAX_STREAM_RETRY_COUNT) {
                            throw e
                        }
                        retryCount++
                        response.closeQuietly()
                        body.closeQuietly()
                        bodyStream.closeQuietly()
                        response = null
                        body = null
                        bodyStream = null
                        continue
                    }

                    break
                }

                if (downloadedLength < entity.length) {
                    throw IOException("Download incomplete: $downloadedLength/${entity.length}")
                }

                showSuccessNotification()
                result = Result.success(
                    workDataOf(DownloadState.STATE to DownloadState.Finished.mask)
                )

            } catch (e: Exception) {
                result = if (e is CancellationException || e.isStoppedCancellation()) {
                    cancelDownloadNotification()
                    mainScope.launch { SonnerToast.info(R.string.download_error_cancelled) }
                    Result.success(
                        workDataOf(DownloadState.STATE to DownloadState.Paused.mask)
                    )
                } else if (e.isRetryableNetworkError() && runAttemptCount < MAX_WORK_RETRY_COUNT) {
                    val reason = e.toDownloadErrorMessage()
                    showRetryNotification(reason)
                    mainScope.launch {
                        SonnerToast.warning(context.getString(R.string.download_task_retrying_s_reason_s, hanimeName, reason))
                    }
                    shouldRetry = true
                    Result.retry()
                } else {
                    val reason = e.toDownloadErrorMessage()
                    showFailureNotification(reason)
                    e.printStackTrace()
                    mainScope.launch {
                        SonnerToast.error(context.getString(R.string.download_task_failed_s_reason_s, hanimeName, reason))
                    }
                    Result.failure(
                        workDataOf(DownloadState.STATE to DownloadState.Failed.mask)
                    )
                }
            } finally {
                val state = DownloadState.from(
                    result.outputData.getInt(DownloadState.STATE, DownloadState.Unknown.mask)
                )
                DatabaseRepo.HanimeDownload.update(
                    entity.copy(
                        state = if (shouldRetry) DownloadState.Queued else state,
                        downloadedLength = downloadedLength
                    )
                )
                raf?.closeQuietly()
                safChannel?.closeQuietly()
                safPfd?.closeQuietly()
                response?.closeQuietly()
                body?.closeQuietly()
                bodyStream?.closeQuietly()
            }
            return@withContext result
        }
    }

    //<editor-fold desc="HLS 下载（nJAV 等只能给清单的站点）">

    /**
     * 下载 HLS 视频：解析清单 → 逐片下载 → 顺序追加拼成一个 MPEG-TS 文件。
     *
     * 为什么不能沿用「字节区间 + 单文件长度」那套：
     * `downloadUrl` 是一份 `.m3u8` 文本，直接 GET 下来只有几 KB，
     * 所以老路径永远卡在 `fetchContentLength()` 返回 null，界面报
     * 「无法获取文件大小或下载信息」。真正的视频在**分片**里。
     *
     * 几个关键决定：
     * - **分片按顺序拼接**就是合法的 MPEG-TS，ExoPlayer / mpv 都能直接播，
     *   所以这里不做转码，也不引入 ffmpeg。文件名后缀走 [HlsPlaylist] 那边
     *   给的 `ts`（见 [io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavParser]）。
     * - `entity.length` 是**抽样估算**的：分片近乎等长，抽十几片就够准，
     *   好过为几千个分片各发一次请求。下载完再把 length 修正成真实字节数。
     * - 断点续传靠一个 sidecar 索引文件（`xxx.ts.hlsidx`），
     *   每写完一片追加一行「下一个分片序号,已落盘字节数」。没有它的话，
     *   中途断网就只能从 0 重下 —— 这种两小时、上 GB 的片子代价太大。
     */
    private suspend fun downloadHls(entity: HanimeDownloadEntity): Result = withContext(Dispatchers.IO) {
        val media = resolveHlsMedia()
        val file = HFileManager.getDownloadVideoFile(
            context = context,
            videoCode = videoCode,
            title = hanimeName,
            quality = quality,
            suffix = videoType,
        )
        file.parentFile?.mkdirs()
        val indexFile = File(file.parentFile, file.name + HLS_INDEX_SUFFIX)
        val sink = openHlsSink(file)
        var downloadedLength = entity.downloadedLength
        var result: Result = Result.failure(workDataOf(DownloadState.STATE to DownloadState.Failed.mask))

        try {
            // 读取上次的续传点
            var startIndex = 0
            var resumeLength = 0L
            if (indexFile.exists()) {
                val last = indexFile.readLines().lastOrNull { it.isNotBlank() }
                val parts = last?.split(',')
                if (parts != null && parts.size == 2) {
                    startIndex = parts[0].toIntOrNull() ?: 0
                    resumeLength = parts[1].toLongOrNull() ?: 0L
                }
            }
            val actualSize = sink.position()
            // 索引与实际文件对不上（权限被清过、上次最后一片没写完）→ 整段重来，
            // 宁可慢也不能交出一个中间缺一段的文件。
            if (startIndex !in 0..media.segments.size || resumeLength > actualSize) {
                startIndex = 0
                resumeLength = 0L
            }
            sink.truncate(resumeLength)
            downloadedLength = resumeLength
            if (startIndex == 0) indexFile.delete()
            LogUtil.d(
                TAG,
                "HLS 开始：共 ${media.segments.size} 片，从第 ${startIndex + 1} 片起，已有 $resumeLength 字节"
            )

            media.initSegment?.let { if (startIndex == 0) fetchHlsSegment(it, sink) }

            var lastUpdate = 0L
            for (i in startIndex until media.segments.size) {
                currentCoroutineContext().ensureActive()
                downloadedLength += fetchHlsSegment(media.segments[i].url, sink)
                indexFile.appendText("${i + 1},$downloadedLength\n")

                val now = System.currentTimeMillis()
                if (now - lastUpdate > RESPONSE_INTERVAL) {
                    lastUpdate = now
                    val progress = (downloadedLength * 100 / entity.length).toInt().coerceIn(0, 100)
                    setProgress(workDataOf(PROGRESS to progress))
                    updateDownloadNotification(progress)
                    DatabaseRepo.HanimeDownload.update(
                        entity.copy(downloadedLength = downloadedLength, state = DownloadState.Downloading)
                    )
                }
            }

            // 全部分片写完 = 完成。顺手把估算的 length 换成真实字节数，
            // 否则下载列表里显示的大小、剩余量与文件对不上。
            indexFile.delete()
            DatabaseRepo.HanimeDownload.update(
                entity.copy(
                    length = downloadedLength,
                    downloadedLength = downloadedLength,
                    state = DownloadState.Finished,
                )
            )
            showSuccessNotification()
            result = Result.success(workDataOf(DownloadState.STATE to DownloadState.Finished.mask))
        } catch (e: Exception) {
            result = if (e is CancellationException || e.isStoppedCancellation()) {
                cancelDownloadNotification()
                DatabaseRepo.HanimeDownload.update(
                    entity.copy(downloadedLength = downloadedLength, state = DownloadState.Paused)
                )
                mainScope.launch { SonnerToast.info(R.string.download_error_cancelled) }
                Result.success(workDataOf(DownloadState.STATE to DownloadState.Paused.mask))
            } else if (e.isRetryableNetworkError() && runAttemptCount < MAX_WORK_RETRY_COUNT) {
                val reason = e.toDownloadErrorMessage()
                showRetryNotification(reason)
                DatabaseRepo.HanimeDownload.update(
                    entity.copy(downloadedLength = downloadedLength, state = DownloadState.Queued)
                )
                mainScope.launch {
                    SonnerToast.warning(
                        context.getString(R.string.download_task_retrying_s_reason_s, hanimeName, reason)
                    )
                }
                Result.retry()
            } else {
                val reason = e.toDownloadErrorMessage()
                showFailureNotification(reason)
                e.printStackTrace()
                DatabaseRepo.HanimeDownload.update(
                    entity.copy(downloadedLength = downloadedLength, state = DownloadState.Failed)
                )
                mainScope.launch {
                    SonnerToast.error(
                        context.getString(R.string.download_task_failed_s_reason_s, hanimeName, reason)
                    )
                }
                Result.failure(workDataOf(DownloadState.STATE to DownloadState.Failed.mask))
            }
        } finally {
            // 索引文件保留，下次接着下
            sink.close()
        }
        return@withContext result
    }

    /** 解析出分片清单：`downloadUrl` 可能是主清单，也可能直接就是分片清单。 */
    private suspend fun resolveHlsMedia(): HlsPlaylist.Media {
        cachedHlsMedia?.let { return it }
        val body = fetchHlsText(downloadUrl)
        val media = if (HlsPlaylist.isMaster(body)) {
            val variants = HlsPlaylist.parseMaster(downloadUrl, body)
            val picked = pickHlsVariant(variants)
                ?: throw IOException("HLS：主清单里没有可用清晰度")
            LogUtil.d(
                TAG,
                "HLS 主清单 ${variants.size} 档，选中 ${picked.height ?: "?"}P（BANDWIDTH=${picked.bandwidth}）"
            )
            HlsPlaylist.parseMedia(picked.url, fetchHlsText(picked.url))
        } else {
            HlsPlaylist.parseMedia(downloadUrl, body)
        }
        if (media.segments.isEmpty()) throw IOException("HLS：清单里没有分片")
        cachedHlsMedia = media
        return media
    }

    /**
     * 挑清晰度：优先命中用户选的那档（`720P` / `1080P`），
     * 认不出来（`自动` / `其他`）就取带宽最高的那档。
     */
    private fun pickHlsVariant(variants: List<HlsPlaylist.Variant>): HlsPlaylist.Variant? {
        if (variants.isEmpty()) return null
        val wanted = QUALITY_HEIGHT.find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (wanted != null) {
            variants.firstOrNull { it.height == wanted }?.let { return it }
        }
        return variants.maxByOrNull { it.bandwidth }
    }

    /** 从 `720P` / `1080P` 里抠出高度。 */
    private val QUALITY_HEIGHT = Regex("""(\d{3,4})""")

    private suspend fun fetchHlsText(url: String): String {
        val request = Request.Builder().url(url).get()
            .apply { downloadHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
        hlsClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.string()
        }
    }

    /**
     * 估算总字节数：等距抽 [HLS_SAMPLE_COUNT] 片问一次大小，再乘以总片数。
     *
     * 用 `Range: bytes=0-0` 而不是 HEAD —— 实测 surrit 的 HEAD 虽然可用，
     * 但这类 CDN 上 HEAD 被拦的比例明显更高，用 1 字节的 GET 最稳。
     */
    private suspend fun estimateHlsLength(media: HlsPlaylist.Media): Long {
        val segments = media.segments
        val sampleCount = minOf(HLS_SAMPLE_COUNT, segments.size)
        val step = (segments.size / sampleCount).coerceAtLeast(1)
        val indices = (0 until sampleCount).map { it * step }.filter { it < segments.size }
        val sizes = coroutineScope {
            indices.map { index ->
                async(Dispatchers.IO) {
                    runCatching { hlsSegmentSize(segments[index].url) }.getOrNull()
                }
            }.awaitAll()
        }
        val known = sizes.filterNotNull()
        if (known.isEmpty()) {
            throw IOException("HLS：抽样探测分片大小失败，拿不到下载总大小")
        }
        val average = known.sum().toDouble() / known.size
        val estimate = (average * segments.size).toLong().coerceAtLeast(1L)
        LogUtil.d(
            TAG,
            "HLS 抽样 ${known.size}/${sampleCount} 片，均 ${average.toLong()} 字节，" +
                "共 ${segments.size} 片 ≈ $estimate 字节，总时长 ${media.totalDurationSeconds.toInt()} 秒"
        )
        return estimate
    }

    private fun hlsSegmentSize(url: String): Long? {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .apply { downloadHeaders.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        return hlsClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
        }
    }

    /** 单个分片：失败自动重试（弱网下偶发的连接重置不该让整个任务重修）。 */
    private suspend fun fetchHlsSegment(url: String, sink: HlsSink): Long {
        var lastError: Throwable? = null
        repeat(HLS_SEGMENT_RETRY) { attempt ->
            try {
                return streamHlsSegment(url, sink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < HLS_SEGMENT_RETRY - 1) {
                    LogUtil.w(TAG, "HLS 分片第 ${attempt + 1} 次失败，准备重试：$url", e)
                    delay(300L * (attempt + 1))
                }
            }
        }
        throw IOException("HLS 分片下载失败：$url", lastError)
    }

    private suspend fun streamHlsSegment(url: String, sink: HlsSink): Long {
        val startPosition = sink.position()
        try {
            val request = Request.Builder().url(url).get()
                .apply { downloadHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
            hlsClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, read)
                        written += read
                    }
                    if (written <= 0L) throw IOException("HLS 分片是空的：$url")
                    return written
                }
            }
        } catch (e: Throwable) {
            // 半截分片必须丢掉，否则重试会接在残片后面，整个 TS 就废了
            runCatching { sink.truncate(startPosition) }
            throw e
        }
    }

    /** HLS 是「追加写 + 可回退到分片边界」，所以只需要一个极简的写入抽象。 */
    private interface HlsSink {
        /** 当前文件长度（也等于下一个字节要写入的位置）。 */
        fun position(): Long

        /** 截断并把写指针移到 [length]。 */
        fun truncate(length: Long)

        fun write(buffer: ByteArray, length: Int)

        fun close()
    }

    /** 优先 SAF（用户自定义下载目录），否则落到应用私有目录。 */
    private fun openHlsSink(file: File): HlsSink {
        val safUri = SafFileManager.getDownloadVideoFileUri(
            context, videoCode, createVideoName(hanimeName, quality, videoType)
        )
        if (safUri != null) {
            val pfd = context.contentResolver.openFileDescriptor(safUri, "rw")
                ?: throw IOException("Open SAF file failed")
            val channel = FileOutputStream(pfd.fileDescriptor).channel
            return object : HlsSink {
                override fun position(): Long = channel.size()

                override fun truncate(length: Long) {
                    channel.truncate(length)
                    channel.position(length)
                }

                override fun write(buffer: ByteArray, length: Int) {
                    val byteBuffer = ByteBuffer.wrap(buffer, 0, length)
                    while (byteBuffer.hasRemaining()) channel.write(byteBuffer)
                }

                override fun close() {
                    channel.closeQuietly()
                    pfd.closeQuietly()
                }
            }
        }
        file.createFileIfNotExists()
        val raf = RandomAccessFile(file, "rwd")
        return object : HlsSink {
            override fun position(): Long = raf.length()

            override fun truncate(length: Long) {
                raf.setLength(length)
                raf.seek(length)
            }

            override fun write(buffer: ByteArray, length: Int) = raf.write(buffer, 0, length)

            override fun close() = raf.closeQuietly()
        }
    }

    //</editor-fold>

    private fun IOException.isStreamResetCancel(): Boolean {
        return message?.contains("stream was reset: CANCEL", ignoreCase = true) == true
    }

    private fun Exception.isStoppedCancellation(): Boolean {
        return isStopped && this is IOException && message.equals("Canceled", ignoreCase = true)
    }

    private fun Exception.isRetryableNetworkError(): Boolean {
        return this is UnknownHostException ||
                this is SocketTimeoutException ||
                this is ConnectException ||
                this is SocketException ||
                (this is IOException && message.equals("Canceled", ignoreCase = true).not())
    }

    private fun Exception.toDownloadErrorMessage(): String {
        return when (this) {
            is UnknownHostException -> context.getString(R.string.download_error_dns)
            is SocketTimeoutException -> context.getString(R.string.download_error_timeout)
            is ConnectException -> context.getString(R.string.download_error_connect)
            is SocketException -> context.getString(R.string.download_error_network)
            is IOException -> {
                val rawMessage = message.orEmpty()
                when {
                    rawMessage.contains("No space", ignoreCase = true) ||
                            rawMessage.contains("Permission", ignoreCase = true) ||
                            rawMessage.contains("Open SAF file failed", ignoreCase = true) -> {
                        context.getString(R.string.download_error_storage)
                    }
                    rawMessage.contains("Download incomplete", ignoreCase = true) -> {
                        context.getString(R.string.download_error_network)
                    }
                    else -> context.getString(R.string.download_error_network)
                }
            }
            else -> localizedMessage?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.unknown_download_error)
        }
    }

    private fun Response.toDownloadErrorMessage(requestNeedRange: Boolean): String {
        return when {
            requestNeedRange && code == 416 -> {
                context.getString(R.string.download_error_range_not_supported)
            }
            requestNeedRange -> context.getString(R.string.download_error_range_not_supported)
            code in 500..599 -> context.getString(R.string.download_error_network)
            else -> message.takeIf { it.isNotBlank() } ?: context.getString(R.string.unknown_download_error)
        }
    }

    private fun FileChannel.writeFully(buffer: ByteArray, length: Int) {
        val byteBuffer = ByteBuffer.wrap(buffer, 0, length)
        while (byteBuffer.hasRemaining()) {
            write(byteBuffer)
        }
    }

    private fun CoroutineScope.updateCoverImage(entity: HanimeDownloadEntity) {
        launch {
            val imgRes = HImageMeower.execute(entity.coverUrl)
            val (os, uri) = SafFileManager.openOutputStreamForCover(
                context, entity.videoCode, entity.title
            )
            val isSuccess = os?.use { out -> imgRes.drawable?.saveTo(out) == true } ?: false
            if (isSuccess && uri != null) {
                val coverUriStr = uri.toString()
                withContext(Dispatchers.IO) {
                    DatabaseRepo.HanimeDownload.update(
                        entity.copy(coverUri = coverUriStr)
                    )
                }
                entity.coverUri = coverUriStr
            }
        }
    }

    private fun createDownloadNotification(progress: Int = 0): Notification {
        return NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_new)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(context.getString(R.string.downloading_s, hanimeName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText("$progress%")
            .setProgress(100, progress, false)
            .build()
    }

    private fun cancelDownloadNotification() {
        notificationManager.cancel(downloadId)
    }

    @SuppressLint("MissingPermission")
    private fun updateDownloadNotification(progress: Int) {
        notificationManager.notify(downloadId, createDownloadNotification(progress))
    }

    private fun createForegroundInfo(progress: Int = 0): ForegroundInfo {
        val notification = createDownloadNotification(progress)
        return ForegroundInfo(
            downloadId, notification,
            // #issue-34: 這裡的參數是為了讓 Android 14 以上的系統可以正常顯示前景通知
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    @SuppressLint("MissingPermission")
    private fun showSuccessNotification() {
        notificationManager.notify(
            downloadId, NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_check_circle)
                .setContentTitle(context.getString(R.string.download_task_completed))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentText(context.getString(R.string.download_completed_s, hanimeName))
                .build()
        )
    }

    @SuppressLint("MissingPermission")
    private fun showFileExistsFailureNotification(fileName: String) {
        notificationManager.notify(
            downloadId, NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_cancel_circle)
                .setContentTitle(context.getString(R.string.this_data_exists))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentText(context.getString(R.string.download_failed_s_exists, fileName))
                .build()
        )
    }

    @SuppressLint("MissingPermission")
    private fun showFailureNotification(errMsg: String? = null) {
        notificationManager.notify(
            downloadId, NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_cancel_circle)
                .setContentTitle(context.getString(R.string.download_task_failed))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentText(
                    context.getString(
                        R.string.download_task_failed_s_reason_s,
                        hanimeName, errMsg ?: context.getString(R.string.unknown_download_error)
                    )
                )
                .build()
        )
    }

    @SuppressLint("MissingPermission")
    private fun showRetryNotification(reason: String) {
        notificationManager.notify(
            downloadId, NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getString(R.string.download_task_retrying))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentText(
                    context.getString(
                        R.string.download_task_retrying_s_reason_s,
                        hanimeName, reason
                    )
                )
                .build()
        )
    }
}
