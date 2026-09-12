package io.github.daisukikaffuchino.han1meviewer.worker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.UPDATE_NOTIFICATION_CHANNEL
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateDownloader
import io.github.daisukikaffuchino.han1meviewer.logic.DownloadProgress
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.formatFileSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/**
 * 应用内更新的下载任务。
 *
 * 用 WorkManager 而不是在 ViewModel 里直接下载：28 MB 的包在弱网下要几十秒，
 * 用户切后台/锁屏时 ViewModel 的协程会被一起回收，WorkManager + 前台通知才能稳。
 */
class AppUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "AppUpdateWorker"

        const val KEY_URL = "url"
        const val KEY_TARGET_VERSION_CODE = "target_version_code"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_ERROR = "error"

        /**
         * `KEY_PROGRESS` 的哨兵值，表示「算不出百分比」。
         *
         * 不能拿 0 代替 —— 「0%」和「不知道百分之几」是两回事：前者能让进度条走确定态、
         * 只是还没开始，后者只能走不确定态。界面靠这个区分。
         */
        const val PROGRESS_UNKNOWN = -1

        private fun workManager(context: Context) = WorkManager.getInstance(context)

        /** 入队一次下载。重复点击用 REPLACE 覆盖，不会并排跑两个。 */
        fun enqueue(context: Context, url: String, targetVersionCode: Int) {
            val request = OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_TARGET_VERSION_CODE to targetVersionCode,
                    )
                )
                .build()
            workManager(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            workManager(context).cancelUniqueWork(TAG)
        }

        /**
         * UI 订阅下载状态。
         *
         * 这里只做「WorkManager 状态 → 业务状态」的映射；**不**在这里决定要不要装，
         * 「已经装过就别再弹安装器」的判断放在 ViewModel（见 `HomePageViewModel`）。
         */
        fun observe(context: Context): Flow<AppUpdateWorkState> =
            workManager(context).getWorkInfosByTagFlow(TAG).map { infos ->
                // 唯一任务，只会有 0 或 1 个
                val info = infos.firstOrNull()
                    ?: return@map AppUpdateWorkState.Idle

                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                        AppUpdateWorkState.Pending

                    WorkInfo.State.RUNNING ->
                        AppUpdateWorkState.Running(
                            // 哨兵值 → null：界面上「不知道百分之几」要能走不确定进度条，
                            // 而不是老老实实显示一条不动的 0% 进度条（那看起来就是卡死）。
                            progress = info.progress.getInt(KEY_PROGRESS, PROGRESS_UNKNOWN)
                                .takeIf { it >= 0 },
                            // 哪怕百分比算不出来，这个数字也一直在涨 ——
                            // 它是「真的在下」的唯一硬证据。
                            bytes = info.progress.getLong(KEY_DOWNLOADED_BYTES, 0L),
                        )

                    WorkInfo.State.SUCCEEDED ->
                        AppUpdateDownloader.existingApkFileOrNull()
                            ?.let {
                                AppUpdateWorkState.Finished(
                                    apkFile = it,
                                    // 由 doWork() 成功时写进 outputData
                                    targetVersionCode = info.outputData.getInt(
                                        KEY_TARGET_VERSION_CODE,
                                        0,
                                    ),
                                )
                            }
                            ?: AppUpdateWorkState.Idle

                    WorkInfo.State.FAILED ->
                        AppUpdateWorkState.Failed(
                            message = info.outputData.getString(KEY_ERROR)
                        )

                    WorkInfo.State.CANCELLED -> AppUpdateWorkState.Idle
                }
            }
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val notificationId = Random.nextInt()

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL).orEmpty()
        val targetVersionCode = inputData.getInt(KEY_TARGET_VERSION_CODE, 0)
        if (url.isBlank()) {
            LogUtil.e(TAG, "更新包 URL 为空，放弃下载")
            return Result.failure(workDataOf(KEY_ERROR to "更新包地址为空"))
        }

        setForeground(createForegroundInfo(DownloadProgress(percent = null, bytes = 0L)))
        return try {
            AppUpdateDownloader.download(url) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to (progress.percent ?: PROGRESS_UNKNOWN),
                        KEY_DOWNLOADED_BYTES to progress.bytes,
                    )
                )
                updateNotification(progress)
            }
            notificationManager.cancel(notificationId)
            Result.success(workDataOf(KEY_TARGET_VERSION_CODE to targetVersionCode))
        } catch (t: Throwable) {
            LogUtil.e(TAG, "更新包下载失败", t)
            notificationManager.cancel(notificationId)
            AppUpdateDownloader.clearApkFile()
            Result.failure(workDataOf(KEY_ERROR to t.message))
        }
    }

    private fun createNotification(progress: DownloadProgress): Notification =
        NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(context.getString(R.string.downloading_update))
            .setContentText(notificationText(progress))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 算不出百分比时走「不确定」进度条，而不是一条骗人的 0% 实心条
            .setProgress(100, progress.percent ?: 0, progress.percent == null)
            .setContentIntent(openAppIntent())
            .build()

    /**
     * 通知的副标题。
     *
     * 有百分比就报百分比；没有就报**已下载字节数** —— 数值一直在涨，用户一眼能看出
     * 「在下，只是不知道总量」，这比干巴巴一句「正在下载…」有用得多。
     */
    private fun notificationText(progress: DownloadProgress): String =
        progress.percent?.let { context.getString(R.string.downloading_update_percent, it) }
            ?: context.getString(R.string.downloading_update_bytes, progress.bytes.formatFileSize())

    @SuppressLint("MissingPermission")
    private fun updateNotification(progress: DownloadProgress) {
        notificationManager.notify(notificationId, createNotification(progress))
    }

    private fun createForegroundInfo(progress: DownloadProgress): ForegroundInfo = ForegroundInfo(
        notificationId,
        createNotification(progress),
        // Android 14+ 前台通知必须声明类型
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    /** 点通知把 App 拉回前台，ViewModel 会在恢复时接着走「安装」这一步。 */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** 更新包下载任务的对外状态。 */
sealed interface AppUpdateWorkState {
    /** 没有任务，或产物已被清理 */
    data object Idle : AppUpdateWorkState

    /** 已入队、正在等网络 */
    data object Pending : AppUpdateWorkState

    /**
     * 正在下载。
     *
     * @param progress 0..100；`null` = 服务端没给 `Content-Length`，算不出百分比。
     * @param bytes 已落盘字节数 —— 没有百分比时，界面靠它「一直在涨」证明下载还活着。
     */
    data class Running(val progress: Int?, val bytes: Long) : AppUpdateWorkState

    /** 下载完成，可以装 */
    data class Finished(
        val apkFile: java.io.File,
        val targetVersionCode: Int,
    ) : AppUpdateWorkState

    data class Failed(val message: String?) : AppUpdateWorkState
}
