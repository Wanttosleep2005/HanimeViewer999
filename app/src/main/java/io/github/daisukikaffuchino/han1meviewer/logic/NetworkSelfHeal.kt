package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.HanimeConstants
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorNode
import io.github.daisukikaffuchino.han1meviewer.logic.model.RelayNode
import io.github.daisukikaffuchino.han1meviewer.logic.network.MirrorStore
import io.github.daisukikaffuchino.han1meviewer.logic.network.RelayNodeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 自愈流程里的一步。用 [kind] 而不是字面标题，标题的本地化交给 UI。 */
enum class HealStepKind {
    /** 给全部镜像测速。 */
    MirrorProbe,

    /** 给全部中转节点测速。 */
    RelayProbe,

    /** 把镜像切到最快的那台。 */
    MirrorApply,

    /** 把中转节点切到最快的那台。 */
    RelayApply,

    /** 复测，确认改完之后确实通。 */
    Verify,
}

enum class HealStatus {
    /** 检查通过、无需改动。 */
    Ok,

    /** 做了改动（例如切了镜像 / 换了节点）。 */
    Changed,

    /** 条件不满足，跳过（例如一个都没测通）。 */
    Skipped,

    /** 这一步本身失败了。 */
    Failed,
}

data class HealStep(
    val kind: HealStepKind,
    /** 具体说明，例如「hanime1.com · 182 毫秒」或「已切到 hanime1.me」。 */
    val detail: String,
    val status: HealStatus,
)

data class HealReport(
    val steps: List<HealStep> = emptyList(),
    val finished: Boolean = false,
) {
    val changedCount: Int get() = steps.count { it.status == HealStatus.Changed }
    val failedCount: Int get() = steps.count { it.status == HealStatus.Failed }
    val allFine: Boolean get() = changedCount == 0 && failedCount == 0
}

/**
 * 一次自愈的落库记录。
 *
 * **只存数字，不存文案。** 摘要交给界面按当前语言现拼 —— 否则英文用户在中文 locale
 * 跑过一次自愈，历史记录里会永久留一条中文，之后切回英文也改不回来。
 */
@Serializable
data class HealLogEntry(
    val at: Long = System.currentTimeMillis(),
    val changed: Int = 0,
    val failed: Int = 0,
    /** 是否改了镜像 —— 改镜像需要重启才生效，历史里要能看出来。 */
    val restartedMirror: Boolean = false,
) {
    /** 这次是否「什么都没动、也没失败」，UI 用它上好/坏色。 */
    val allFine: Boolean get() = changed == 0 && failed == 0
}

/**
 * 一键网络自愈。
 *
 * ## 为什么需要一个「合起来做」的入口
 *
 * 到 9.2 为止，网络相关的诊断与修复能力已经齐了，但**散着**：
 * - 8.2 的网络诊断能告诉你「哪一环断了」，但它只出结论、不动手；
 * - 9.0 的中转节点池能自动优选节点，但它不知道镜像那边通不通；
 * - 9.1 的镜像池能测速选最快，但不会顺手把中转也一起看了。
 *
 * 结果就是用户遇到「加载不出来」时，得自己判断该去点哪个、按什么顺序点。
 * 这个类把它们串成**一个动作**：测速 → 自动落到最优 → 复测确认 → 记账。
 *
 * ## 刻意的界限
 *
 * - **只动「选哪个」这一层**，不动开关。不会因为自愈就替用户打开代理、打开 DoH、
 *   或把中转从关变开 —— 那些是用户的决定。
 * - **镜像变更会明说是「需重启」**。选项切换后首页缓存与数据源都要跟着重建，
 *   静默替换只会让人以为界面坏了。
 * - 每一步都发一次（[Flow]），所以界面能像诊断那样**边跑边出结果**，
 *   而不是盯着转圈等十几秒。
 */
object NetworkSelfHeal {

    private const val LOG_LIMIT = 10

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun log(): List<HealLogEntry> =
        runCatching { json.decodeFromString<List<HealLogEntry>>(SettingsRepository.selfHealLogJson) }
            .getOrDefault(emptyList())

    suspend fun clearLog() {
        SettingsRepository.update { it.copy(selfHealLogJson = "") }
    }

    /**
     * 跑一次自愈。每完成一步发射一次报告，最后一条 `finished = true`。
     *
     * 全程不抛：任何一步失败都记成 [HealStatus.Failed] 并继续 —— 自愈的价值在于
     * 「尽量把能修的修掉」，中途一个环节出错就整体中断反而是最差的结果。
     *
     * @param builtInRelayName 内置中转节点的**本地化**名字。这一层拿不到 Context，
     *   不能让 UI 文案硬编码在这里，所以由界面把 `relay_node_builtin` 传进来。
     */
    fun run(builtInRelayName: String): Flow<HealReport> = flow {
        val steps = mutableListOf<HealStep>()
        suspend fun publish() = emit(HealReport(steps = steps.toList(), finished = false))

        // ── 1. 镜像测速 ───────────────────────────────────────────────
        var fastestMirror: MirrorNode? = null
        try {
            val probes = withContext(Dispatchers.IO) { MirrorStore.probeAll() }
            val best = MirrorStore.fastest()
            fastestMirror = best
            steps += if (best == null) {
                HealStep(HealStepKind.MirrorProbe, "0/${probes.size}", HealStatus.Failed)
            } else {
                // 探测本身成功就是 Ok —— 「要不要切」由第 3 步汇报，不要在这一步提前下结论。
                HealStep(
                    HealStepKind.MirrorProbe,
                    "${MirrorStore.displayName(best)} · ${probes[best.id]?.latencyMs ?: -1} ms",
                    HealStatus.Ok,
                )
            }
        } catch (t: Throwable) {
            steps += HealStep(HealStepKind.MirrorProbe, t.message.orEmpty(), HealStatus.Failed)
        }
        publish()

        // ── 2. 中转节点测速 ──────────────────────────────────────────
        var fastestRelay: RelayNode? = null
        try {
            val health = withContext(Dispatchers.IO) { RelayNodeStore.checkAll() }
            val nodesById = RelayNodeStore.allNodes().associateBy { it.id }
            val best = health.values
                .filter { it.healthy && it.latencyMs >= 0 && it.nodeId in nodesById }
                .minByOrNull { it.latencyMs }
            val picked = best?.let { nodesById[it.nodeId] }
            fastestRelay = picked
            steps += if (best == null || picked == null) {
                HealStep(HealStepKind.RelayProbe, "0/${health.size}", HealStatus.Failed)
            } else {
                HealStep(
                    HealStepKind.RelayProbe,
                    "${RelayNodeStore.displayName(picked, builtInRelayName)} · ${best.latencyMs} ms",
                    HealStatus.Ok,
                )
            }
        } catch (t: Throwable) {
            steps += HealStep(HealStepKind.RelayProbe, t.message.orEmpty(), HealStatus.Failed)
        }
        publish()

        // ── 3. 应用最快镜像 ─────────────────────────────────────────
        var mirrorChanged = false
        try {
            val target = fastestMirror
            if (target == null) {
                steps += HealStep(HealStepKind.MirrorApply, "", HealStatus.Skipped)
            } else if (target.id == MirrorStore.activeMirror().id) {
                steps += HealStep(
                    HealStepKind.MirrorApply,
                    MirrorStore.displayName(target),
                    HealStatus.Ok,
                )
            } else {
                applyMirror(target)
                mirrorChanged = true
                steps += HealStep(
                    HealStepKind.MirrorApply,
                    MirrorStore.displayName(target),
                    HealStatus.Changed,
                )
            }
        } catch (t: Throwable) {
            steps += HealStep(HealStepKind.MirrorApply, t.message.orEmpty(), HealStatus.Failed)
        }
        publish()

        // ── 4. 应用最快节点 ─────────────────────────────────────────
        try {
            val target = fastestRelay
            if (target == null) {
                steps += HealStep(HealStepKind.RelayApply, "", HealStatus.Skipped)
            } else if (target.id == RelayNodeStore.activeNode().id) {
                steps += HealStep(
                    HealStepKind.RelayApply,
                    RelayNodeStore.displayName(target, builtInRelayName),
                    HealStatus.Ok,
                )
            } else {
                // 手动选中这台，并关掉自动优选 —— 否则下一次读 activeNode 又会按自动
                // 规则算一遍，用户看到的「已切到 X」会与实际不符。
                RelayNodeStore.selectNode(target.id)
                SettingsRepository.update { it.copy(autoSelectRelayNode = false) }
                steps += HealStep(
                    HealStepKind.RelayApply,
                    RelayNodeStore.displayName(target, builtInRelayName),
                    HealStatus.Changed,
                )
            }
        } catch (t: Throwable) {
            steps += HealStep(HealStepKind.RelayApply, t.message.orEmpty(), HealStatus.Failed)
        }
        publish()

        // ── 5. 复测 ────────────────────────────────────────────────
        try {
            val ok = withContext(Dispatchers.IO) { RelayNodeStore.checkActive(force = true) }
            steps += HealStep(
                HealStepKind.Verify,
                "",
                if (ok) HealStatus.Ok else HealStatus.Failed,
            )
        } catch (t: Throwable) {
            steps += HealStep(HealStepKind.Verify, t.message.orEmpty(), HealStatus.Failed)
        }

        val report = HealReport(steps = steps.toList(), finished = true)
        emit(report)
        writeLog(report, mirrorChanged)
    }

    /**
     * 把镜像选择落库。
     *
     * 与 [io.github.daisukikaffuchino.han1meviewer.ui.navigation.settings] 里的手动切换
     * 走同一组字段：自建镜像写进 `customMirrorSite`，内置镜像清掉自定义开关。
     * `siteSource` 必须跟着改，否则会出现「数据源写着 nJAV、域名却是 hanime」的自相矛盾。
     */
    private suspend fun applyMirror(node: MirrorNode) {
        SettingsRepository.update {
            it.copy(
                domainName = node.url,
                selectedBaseUrl = node.url,
                siteSource = HanimeConstants.siteSourceOf(node.url),
                useCustomMirrorSite = !node.builtIn,
                customMirrorSite = if (node.builtIn) it.customMirrorSite else node.url,
                appendCustomMirrorPath = if (node.builtIn) it.appendCustomMirrorPath else false,
            )
        }
    }

    private suspend fun writeLog(report: HealReport, mirrorChanged: Boolean) {
        val entry = HealLogEntry(
            changed = report.changedCount,
            failed = report.failedCount,
            restartedMirror = mirrorChanged,
        )
        val next = (listOf(entry) + log()).take(LOG_LIMIT)
        runCatching {
            SettingsRepository.update { it.copy(selfHealLogJson = json.encodeToString(next)) }
        }
    }
}
