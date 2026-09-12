package io.github.daisukikaffuchino.han1meviewer.logic.entity.download

import androidx.annotation.IntRange
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.github.daisukikaffuchino.han1meviewer.HFileManager
import io.github.daisukikaffuchino.han1meviewer.logic.state.DownloadState
import kotlinx.serialization.Serializable

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2023/08/18 018 21:50
 */
@Serializable
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = DownloadGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_DEFAULT
        )
    ],
    indices = [
        Index(value = ["groupId"])
    ]
)
@TypeConverters(HanimeDownloadEntity.StateTypeConverter::class)
data class HanimeDownloadEntity(
    /**
     * 已下载视频的分组ID
     */
    val groupId: Int = DownloadGroupEntity.DEFAULT_GROUP_ID,
    /**
     * 封面地址
     */
    val coverUrl: String,
    /**
     * 封面图片本地地址
     */
    var coverUri: String?,
    /**
     * 影片标题
     */
    val title: String,
    /**
     * 添加日期
     */
    val addDate: Long,
    /**
     * 影片代码
     */
    val videoCode: String,
    /**
     * 影片存储在本地的位置
     */
    val videoUri: String,
    /**
     * 影片质量
     */
    val quality: String,

    /**
     * 影片下载地址
     */
    val videoUrl: String,
    /**
     * 影片长度
     */
    val length: Long,
    /**
     * 影片已下载长度
     */
    val downloadedLength: Long,
//    /**
//     * 是否正在下载
//     */
//    val isDownloading: Boolean = false,
    /**
     * 当前状态
     */
    val state: DownloadState = DownloadState.Unknown,

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
) {
    /**
     * 下载进度。
     *
     * ⚠️ 必须 clamp：HLS 下载的 [length] 是**抽样估算**值（见
     * `HanimeDownloadWorker.estimateHlsLength`），某个片子估算偏小就会让
     * `downloadedLength / length` 超过 100%，界面上会出现「108%」、
     * 进度条也会溢出（`item.progress / 100f` 直接喂给了
     * `LinearProgressIndicator`，它的进度必须在 0f..1f）。
     */
    @get:IntRange(from = 0, to = 100)
    val progress get() = (downloadedLength * 100 / length).toInt().coerceIn(0, 100)

    val isDownloading get() = state == DownloadState.Downloading

    val suffix get() = videoUri.substringAfterLast(".", HFileManager.DEF_VIDEO_TYPE)

    /**
     * 排序方式
     */
    enum class SortedBy {
        ID, TITLE
    }

    class StateTypeConverter {
        @TypeConverter
        fun from(state: DownloadState): Int = state.mask

        @TypeConverter
        fun to(state: Int): DownloadState = DownloadState.from(state)
    }
}
