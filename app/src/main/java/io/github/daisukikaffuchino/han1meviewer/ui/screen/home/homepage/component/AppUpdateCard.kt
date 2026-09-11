package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticButton as Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateInfo
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview

/**
 * 更新卡片的动作状态。
 *
 * 和 `HomePageViewModel.UpdateDownloadState` 分开定义，是为了让这个纯 UI 组件
 * 不依赖 ViewModel；映射在 `HomePageScreen` 里做。
 */
sealed interface AppUpdateActionState {
    data object Idle : AppUpdateActionState

    /** 正在下载。`progress` 为 null 表示还没拿到 Content-Length，进度条走「不确定」样式 */
    data class Downloading(val progress: Int?) : AppUpdateActionState

    /** 包已下载好，点一下就能装 */
    data object ReadyToInstall : AppUpdateActionState

    data class Failed(val message: String?) : AppUpdateActionState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateCard(
    updateInfo: AppUpdateInfo,
    onUpdateClick: () -> Unit,
    onIgnoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionState: AppUpdateActionState = AppUpdateActionState.Idle,
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_security_update),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.update_available_title, updateInfo.versionName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            if (updateInfo.forceUpdate) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.force_update_notice),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (updateInfo.updateDescription.isNotBlank()) {
                Text(
                    text = updateInfo.updateDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (actionState) {
                is AppUpdateActionState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val progress = actionState.progress
                        if (progress == null) {
                            // 服务端没给 Content-Length，做不了百分比就老老实实走不确定进度条
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = if (progress == null) {
                                stringResource(R.string.downloading_update)
                            } else {
                                stringResource(R.string.downloading_update_percent, progress)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is AppUpdateActionState.Failed -> {
                    Text(
                        text = actionState.message
                            ?: stringResource(R.string.update_download_failed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> Unit
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!updateInfo.forceUpdate && actionState !is AppUpdateActionState.Downloading) {
                    TextButton(onClick = onIgnoreClick) {
                        Text(stringResource(R.string.ignore_this_update))
                    }
                }
                Button(
                    onClick = onUpdateClick,
                    enabled = actionState !is AppUpdateActionState.Downloading,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (actionState) {
                            is AppUpdateActionState.Downloading ->
                                stringResource(R.string.update_downloading_short)

                            is AppUpdateActionState.ReadyToInstall ->
                                stringResource(R.string.update_install_now)

                            else -> stringResource(R.string.update_now)
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Update available")
@Composable
private fun AppUpdateCardPreview() {
    ComponentPreview {
        AppUpdateCard(
            updateInfo = previewUpdateInfo(forceUpdate = false),
            onUpdateClick = {},
            onIgnoreClick = {},
        )
    }
}

@Preview(showBackground = true, name = "Update downloading")
@Composable
private fun AppUpdateCardDownloadingPreview() {
    ComponentPreview {
        AppUpdateCard(
            updateInfo = previewUpdateInfo(forceUpdate = false),
            onUpdateClick = {},
            onIgnoreClick = {},
            actionState = AppUpdateActionState.Downloading(42),
        )
    }
}

@Preview(showBackground = true, name = "Required update")
@Composable
private fun ForcedAppUpdateCardPreview() {
    ComponentPreview {
        AppUpdateCard(
            updateInfo = previewUpdateInfo(forceUpdate = true),
            onUpdateClick = {},
            onIgnoreClick = {},
            actionState = AppUpdateActionState.ReadyToInstall,
        )
    }
}

private fun previewUpdateInfo(forceUpdate: Boolean) = AppUpdateInfo(
    versionName = "26.1.0",
    versionCode = 260720,
    downloadUrl = "https://github.com/daisukiKaffuChino/Han1meViewer/releases/latest",
    updateDescription = "Includes stability improvements and interface refinements.",
    forceUpdate = forceUpdate,
)
