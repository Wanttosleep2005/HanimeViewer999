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
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.utils.LogUtil
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
        const val KEY_ERROR = "error"

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
                            progress = info.progress.getInt(KEY_PROGRESS, 0)
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

        setForeground(createForegroundInfo(0))
        return try {
            AppUpdateDownloader.download(url) { progress ->
                setProgress(workDataOf(KEY_PROGRESS to progress))
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

    private fun createNotification(progress: Int): Notification =
        NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(context.getString(R.string.downloading_update))
            .setContentText(context.getString(R.string.downloading_update_percent, progress))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, false)
            .setContentIntent(openAppIntent())
            .build()

    @SuppressLint("MissingPermission")
    private fun updateNotification(progress: Int) {
        notificationManager.notify(notificationId, createNotification(progress))
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo = ForegroundInfo(
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

    data class Running(val progress: Int) : AppUpdateWorkState

    /** 下载完成，可以装 */
    data class Finished(
        val apkFile: java.io.File,
        val targetVersionCode: Int,
    ) : AppUpdateWorkState

    data class Failed(val message: String?) : AppUpdateWorkState
}
