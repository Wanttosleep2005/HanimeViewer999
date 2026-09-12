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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.RelayNode
import io.github.daisukikaffuchino.han1meviewer.logic.model.RelayNodeHealth
import io.github.daisukikaffuchino.han1meviewer.logic.model.RelayNodeValidation
import io.github.daisukikaffuchino.han1meviewer.ui.component.FilledIconButton
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticTextButton as TextButton

/** 节点管理弹窗需要展示的全部状态。 */
data class RelayNodeUiState(
    val nodes: List<RelayNode> = emptyList(),
    val health: Map<String, RelayNodeHealth> = emptyMap(),
    /** 当前**生效**的节点 id（自动优选算出来的也算）。 */
    val activeNodeId: String = "",
    val autoSelect: Boolean = true,
    val testing: Boolean = false,
    /** 上一次添加节点的校验结果，用于在表单里回显错误。 */
    val lastValidation: RelayNodeValidation? = null,
    val builtInName: String = "",
)

/** 节点管理弹窗的事件出口。 */
data class RelayNodeActions(
    val onTest: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onAutoSelectChange: (Boolean) -> Unit = {},
    val onAdd: (host: String, port: String, secret: String, label: String) -> Unit = { _, _, _, _ -> },
    val onRemove: (String) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * 中转节点管理。
 *
 * ## 为什么是弹窗而不是独立页面
 *
 * 这一屏只服务一个开关（CDN 中转），拆成独立路由反而让用户多跳两层，
 * 而它本身是「改完就关」的配置面板。用全宽弹窗既保住了空间，
 * 又不必在导航图里新开一个入口。
 */
@Composable
fun RelayNodesDialog(
    state: RelayNodeUiState,
    actions: RelayNodeActions,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("443") }
    var secret by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.relay_nodes_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── 自动优选 ─────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.autoSelect,
                            onClick = { actions.onAutoSelectChange(!state.autoSelect) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(checked = state.autoSelect, onCheckedChange = null)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.relay_auto_select))
                        Text(
                            text = stringResource(R.string.relay_auto_select_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.relay_nodes_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FilledIconButton(
                        onClick = actions.onTest,
                        enabled = !state.testing,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_speed),
                            contentDescription = stringResource(R.string.relay_node_test),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                if (state.testing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                state.nodes.forEach { node ->
                    RelayNodeRow(
                        node = node,
                        builtInName = state.builtInName,
                        health = state.health[node.id],
                        isActive = node.id == state.activeNodeId,
                        canRemove = node.id != "builtin",
                        onSelect = { actions.onSelect(node.id) },
                        onRemove = { actions.onRemove(node.id) },
                    )
                }

                // ── 新增节点 ─────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.relay_node_add),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.relay_node_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(24) },
                            label = { Text(stringResource(R.string.relay_node_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.relay_node_secret)) },
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.relay_node_secret_summary)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    state.lastValidation?.takeIf { it != RelayNodeValidation.Ok }?.let { validation ->
                        Text(
                            text = stringResource(validation.messageRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    TextButton(
                        onClick = { actions.onAdd(host, port, secret, label) },
                    ) {
                        Text(stringResource(R.string.relay_node_add))
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
private fun RelayNodeRow(
    node: RelayNode,
    builtInName: String,
    health: RelayNodeHealth?,
    isActive: Boolean,
    canRemove: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = when {
        node.id == "builtin" -> builtInName.ifBlank { node.displayHost }
        node.label.isNotBlank() -> node.label
        else -> node.displayHost
    }

    // 三级状态：绿=通、红=连续失败到阈值、灰=还没测过。刻意区分「没测过」和「测了不通」——
    // 前者只是没数据，后者才是真有问题。
    val (statusColor, statusText) = when {
        health == null -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.relay_node_untested)
        health.healthy && health.latencyMs >= 0 ->
            Color(0xFF4CAF50) to stringResource(R.string.relay_node_latency, health.latencyMs)

        health.healthy -> Color(0xFFFFC107) to stringResource(R.string.relay_node_untested)
        else -> Color(0xFFF44336) to stringResource(R.string.relay_node_unreachable)
    }

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
            Text(text = name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${node.displayHost} · $statusText",
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
        }
        if (canRemove) {
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

private fun RelayNodeValidation.messageRes(): Int = when (this) {
    RelayNodeValidation.Ok -> R.string.relay_node_error_none
    RelayNodeValidation.EmptyHost -> R.string.relay_node_error_empty_host
    RelayNodeValidation.InvalidHost -> R.string.relay_node_error_invalid_host
    RelayNodeValidation.InvalidPort -> R.string.relay_node_error_invalid_port
    RelayNodeValidation.EmptySecret -> R.string.relay_node_error_empty_secret
    RelayNodeValidation.InvalidSecret -> R.string.relay_node_error_invalid_secret
    RelayNodeValidation.Duplicate -> R.string.relay_node_error_duplicate
}
