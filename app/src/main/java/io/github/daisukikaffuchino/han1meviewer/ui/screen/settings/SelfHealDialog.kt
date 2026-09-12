package io.github.daisukikaffuchino.han1meviewer.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.HealLogEntry
import io.github.daisukikaffuchino.han1meviewer.logic.HealReport
import io.github.daisukikaffuchino.han1meviewer.logic.HealStatus
import io.github.daisukikaffuchino.han1meviewer.logic.HealStep
import io.github.daisukikaffuchino.han1meviewer.logic.HealStepKind
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticTextButton as TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一键自愈弹窗的状态。 */
data class SelfHealUiState(
    /** null = 还没跑过。 */
    val report: HealReport? = null,
    val running: Boolean = false,
    /** 历史记录，新的在前。 */
    val log: List<HealLogEntry> = emptyList(),
    /** 本次自愈改了镜像 —— 需要提示重启。 */
    val needsRestart: Boolean = false,
)

/** 一键自愈弹窗的事件出口。 */
data class SelfHealActions(
    val onRun: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onClearLog: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * 一键网络自愈。
 *
 * 与 [DiagDialog]（只出结论、不动手）互补：这里**会动手**，所以每一步都要写清「改了什么」。
 * 三步半的信息必须同时在屏幕上：
 * 1. 正在跑什么（进度条 + 逐步冒出来的步骤）；
 * 2. 结论（改动几处、失败几处）；
 * 3. 副作用（切了镜像 → 必须重启，否则用户会以为「点了没用」）；
 * 4. 历史（上次是不是也这样 —— 偶发和常态要能区分）。
 */
@Composable
fun SelfHealDialog(
    state: SelfHealUiState,
    actions: SelfHealActions,
) {
    val timeFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val steps = state.report?.steps.orEmpty()
    val runLabel =
        if (state.report == null) R.string.self_heal_run else R.string.self_heal_rerun

    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.self_heal_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.self_heal_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                steps.forEach { step -> HealStepRow(step) }

                val report = state.report
                if (report != null && report.finished) {
                    Text(
                        text = if (report.allFine) {
                            stringResource(R.string.self_heal_log_fine)
                        } else {
                            stringResource(
                                R.string.self_heal_result,
                                report.changedCount,
                                report.failedCount,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = reportColor(report.changedCount, report.failedCount),
                    )
                }

                // 改了镜像必须明说「要重启」。静默替换会让人以为界面坏了 —— 首页缓存和
                // 数据源都还挂在旧站点上，不重启是看不到变化的。
                if (state.needsRestart) {
                    Text(
                        text = stringResource(R.string.self_heal_restart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFC107),
                    )
                    TextButton(onClick = actions.onRestart) {
                        Text(stringResource(R.string.self_heal_restart_now))
                    }
                }

                // ── 历史 ────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.self_heal_history),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.log.isNotEmpty()) {
                            TextButton(onClick = actions.onClearLog) {
                                Text(stringResource(R.string.self_heal_clear_log))
                            }
                        }
                    }
                    if (state.log.isEmpty()) {
                        Text(
                            text = stringResource(R.string.self_heal_history_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.log.forEach { entry -> HealLogRow(entry, timeFormatter) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onRun, enabled = !state.running) {
                Text(stringResource(runLabel))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
}

@Composable
private fun HealStepRow(step: HealStep) {
    val color = when (step.status) {
        HealStatus.Ok -> Color(0xFF4CAF50)
        HealStatus.Changed -> Color(0xFFFFC107)
        HealStatus.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
        HealStatus.Failed -> Color(0xFFF44336)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "●", color = color)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(step.kind.titleRes()),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = listOfNotNull(
                    stringResource(step.status.labelRes()),
                    step.detail.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

@Composable
private fun HealLogRow(entry: HealLogEntry, formatter: SimpleDateFormat) {
    val summary = if (entry.allFine) {
        stringResource(R.string.self_heal_log_fine)
    } else {
        stringResource(R.string.self_heal_result, entry.changed, entry.failed)
    }
    val restartTag = stringResource(R.string.self_heal_log_restart_tag)
        .takeIf { entry.restartedMirror }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatter.format(Date(entry.at)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = listOfNotNull(summary, restartTag).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = reportColor(entry.changed, entry.failed),
        )
    }
}

/** 结论配色：有失败=红，有改动=琥珀，全好=绿。 */
private fun reportColor(changed: Int, failed: Int): Color = when {
    failed > 0 -> Color(0xFFF44336)
    changed > 0 -> Color(0xFFFFC107)
    else -> Color(0xFF4CAF50)
}

private fun HealStepKind.titleRes(): Int = when (this) {
    HealStepKind.MirrorProbe -> R.string.self_heal_step_mirror_probe
    HealStepKind.RelayProbe -> R.string.self_heal_step_relay_probe
    HealStepKind.MirrorApply -> R.string.self_heal_step_mirror_apply
    HealStepKind.RelayApply -> R.string.self_heal_step_relay_apply
    HealStepKind.Verify -> R.string.self_heal_step_verify
}

private fun HealStatus.labelRes(): Int = when (this) {
    HealStatus.Ok -> R.string.self_heal_status_ok
    HealStatus.Changed -> R.string.self_heal_status_changed
    HealStatus.Skipped -> R.string.self_heal_status_skipped
    HealStatus.Failed -> R.string.self_heal_status_failed
}
