package io.github.daisukikaffuchino.han1meviewer.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorNode
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorProbe
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorValidation
import io.github.daisukikaffuchino.han1meviewer.ui.component.FilledIconButton
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticTextButton as TextButton

/** 镜像管理弹窗需要展示的全部状态。 */
data class MirrorUiState(
    val mirrors: List<MirrorNode> = emptyList(),
    val probes: Map<String, MirrorProbe> = emptyMap(),
    /** 当前**生效**的镜像 id。 */
    val activeMirrorId: String = "",
    /** 出厂默认镜像的 id，用于标「默认」。 */
    val defaultMirrorId: String = "",
    val testing: Boolean = false,
    /** 上一次添加的校验结果，用于在表单里回显错误。 */
    val lastValidation: MirrorValidation? = null,
)

/** 镜像管理弹窗的事件出口。 */
data class MirrorActions(
    val onTest: () -> Unit = {},
    val onUseFastest: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onAdd: (url: String, label: String) -> Unit = { _, _ -> },
    val onRemove: (String) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * 镜像站管理。
 *
 * 与 9.0 的中转节点弹窗同构，但少了两样东西、多了一样：
 * - 少「自动优选」开关 —— 镜像是**入口**，切换它需要重启应用（首页缓存、数据源都跟着变），
 *   做成「后台自动切」会让用户在不经意间换了站点，比手动点一下更困惑；
 * - 少「口令」字段 —— 镜像就是公开域名，没有鉴权；
 * - 多「用最快的」按钮 —— 测速之后一键落到延迟最低的那台。
 */
@Composable
fun MirrorPoolDialog(
    state: MirrorUiState,
    actions: MirrorActions,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.mirror_pool_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledIconButton(
                        onClick = actions.onTest,
                        enabled = !state.testing,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_speed),
                            contentDescription = stringResource(R.string.mirror_test),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TextButton(onClick = actions.onUseFastest, enabled = !state.testing) {
                        Text(stringResource(R.string.mirror_use_fastest))
                    }
                }

                if (state.testing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                state.mirrors.forEach { mirror ->
                    MirrorRow(
                        mirror = mirror,
                        probe = state.probes[mirror.id],
                        isActive = mirror.id == state.activeMirrorId,
                        isDefault = mirror.id == state.defaultMirrorId,
                        onSelect = { actions.onSelect(mirror.id) },
                        onRemove = { actions.onRemove(mirror.id) },
                    )
                }

                // ── 添加镜像 ─────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.mirror_add),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.mirror_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(24) },
                        label = { Text(stringResource(R.string.mirror_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    state.lastValidation?.takeIf { it != MirrorValidation.Ok }?.let { validation ->
                        Text(
                            text = stringResource(validation.messageRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    TextButton(onClick = { actions.onAdd(url, label) }) {
                        Text(stringResource(R.string.mirror_add))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {},
    )
}

@Composable
private fun MirrorRow(
    mirror: MirrorNode,
    probe: MirrorProbe?,
    isActive: Boolean,
    isDefault: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    // 三级状态：绿=通、红=测了不通、灰=还没测。刻意区分「没测过」与「测了不通」——
    // 前者只是没数据，后者才是真有问题。
    val (statusColor, statusText) = when {
        probe == null ->
            MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.mirror_untested)

        probe.reachable && probe.latencyMs >= 0 ->
            Color(0xFF4CAF50) to stringResource(R.string.mirror_latency, probe.latencyMs)

        else -> Color(0xFFF44336) to stringResource(R.string.mirror_unreachable)
    }

    val builtInTag = if (mirror.builtIn) stringResource(R.string.mirror_builtin) else ""
    val defaultTag = if (isDefault) stringResource(R.string.mirror_default) else ""
    val tags = listOf(builtInTag, defaultTag).filter { it.isNotBlank() }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isActive, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = isActive, onClick = null)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = mirrorName(mirror), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = listOfNotNull(
                    mirror.displayHost.takeIf { it != mirrorName(mirror) },
                    statusText,
                    tags.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
        }
        if (!mirror.builtIn) {
            FilledIconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
    }
}

private fun mirrorName(mirror: MirrorNode): String =
    mirror.label.ifBlank { mirror.displayHost }

private fun MirrorValidation.messageRes(): Int = when (this) {
    MirrorValidation.Ok -> R.string.mirror_added
    MirrorValidation.Empty -> R.string.mirror_err_empty
    MirrorValidation.Invalid -> R.string.mirror_err_invalid
    MirrorValidation.Duplicate -> R.string.mirror_err_duplicate
}
