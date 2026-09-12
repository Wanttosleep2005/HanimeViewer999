package io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateCheckResult
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AppUpdateActionState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AppUpdateCard

/**
 * 「关于」页手动检查更新的结果弹窗。
 *
 * 一次检查会拿到两个互相独立的结果，这里**分开显示，不合并**：
 *
 * 1. **本仓库（mod 线）** —— 有可安装的新版本时直接把 [AppUpdateCard] 嵌进来，
 *    下载 / 安装的交互与首页更新卡片完全一致（同一套 `AppUpdateWorker`）。
 * 2. **上游 `daisukiKaffuChino/Han1meViewer`** —— 只用于告知。上游包签名与本 fork 不同、
 *    versionCode 也更低，**装不上**（见 `UpstreamReleaseInfo` 的说明），所以这里不给「更新」按钮，
 *    只留一个打开发布页的入口，方便手动合并。
 *
 * 上游查询失败时**照样显示失败原因**，而不是假装「已是最新」—— 这正是这次要修的问题：
 * 原来上游那条路根本走不出去，界面上却什么都看不出来。
 */
@Composable
fun AppUpdateCheckDialog(
    result: AppUpdateCheckResult,
    actionState: AppUpdateActionState,
    onDismiss: () -> Unit,
    onUpdateClick: () -> Unit,
    onOpenUpstream: () -> Unit,
) {
    val updateInfo = result.updateInfo
    val upstream = result.upstream
    val upstreamError = result.upstreamError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_for_updates)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.update_check_current_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- 本构建（mod 线）----
                if (updateInfo == null) {
                    Text(
                        text = stringResource(R.string.update_check_build_up_to_date),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.check_update_summary_available,
                            updateInfo.versionName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    AppUpdateCard(
                        updateInfo = updateInfo,
                        onUpdateClick = onUpdateClick,
                        onIgnoreClick = {},
                        actionState = actionState,
                        showIgnoreButton = false,
                    )
                }

                // ---- 上游版本（仅告知）----
                when {
                    upstream != null -> {
                        Text(
                            text = stringResource(
                                R.string.update_check_upstream_latest,
                                upstream.version,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (upstream.isNewerThanInstalled) {
                            Text(
                                text = stringResource(
                                    R.string.update_check_upstream_newer,
                                    upstream.version,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (upstream.changelog.isNotBlank()) {
                            Text(
                                text = upstream.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 8,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }

                    upstreamError != null -> {
                        Text(
                            text = stringResource(
                                R.string.update_check_upstream_failed,
                                upstreamError,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (upstream != null) {
                    TextButton(onClick = onOpenUpstream) {
                        Text(stringResource(R.string.open_release_page))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    )
}
