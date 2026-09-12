package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.RelayNode
import io.github.daisukikaffuchino.han1meviewer.logic.model.RelayNodeHealth
import io.github.daisukikaffuchino.han1meviewer.logic.model.RelayNodeValidation
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.unsafeLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 中转节点池。
 *
 * ## 为什么要从「写死一个端点」升级成池
 *
 * 8.0 里中转地址是 `const val` 写死在代码里的 —— 那是为了尽快把视频救活，
 * 代价是**这台机器成了单点**：它挂了、IP 被临时封了、换了台服务器，
 * 用户除了等新版本没有任何办法，而「等新版本」意味着「这几天看不了视频」。
 *
 * 9.0 把它改成可配置的节点池：
 *
 * ```
 * 内置节点（写死，不可删） ─┐
 *                          ├─▶ 健康检查 ─▶ 优选 ─▶ 生效节点
 * 用户自建节点（可增删） ───┘         ↑
 *                              失败计数 / 故障转移
 * ```
 *
 * ## 三个刻意的设计
 *
 * 1. **内置节点永远在池子里且不可删。** 用户删光自建节点、或写错了配置，
 *    至少还能回到「出厂可用」的状态。少一个救生圈的成本远大于多一行代码。
 * 2. **自动优选只在「真的测过」的基础上做。** 从没测过的节点不参与排序 ——
 *    否则一次启动就会因为「另一台机器碰巧没测过」而切走，行为不可预测。
 * 3. **健康结论有 TTL 且带失败计数。** 见 [HEALTH_TTL_MS] 与 [FAILURE_THRESHOLD]。
 */
object RelayNodeStore {

    private const val TAG = "RelayNodeStore"

    /** 内置节点的固定 id。 */
    const val BUILT_IN_ID = "builtin"

    /** 健康检查结论的有效期。过期后自动优选退回「内置」，行为可预测。 */
    private const val HEALTH_TTL_MS = 10 * 60_000L

    /**
     * 连续失败几次才把节点判为不健康。
     *
     * 刻意不是 1：一次网络抖动（切 Wi-Fi、地铁进隧道）不该让节点被踢出优选。
     * 但也不能太大 —— 3 次已经足够覆盖「服务器真的挂了」。
     */
    private const val FAILURE_THRESHOLD = 3

    /** 单次探活超时。中转要么秒回要么不通，5 s 足够。 */
    private const val PROBE_TIMEOUT_MS = 5_000L

    /**
     * 内置节点。
     *
     * 用 [CdnRelay.HOST] / [CdnRelay.PORT] / [CdnRelay.SECRET]，保证与 8.x 的
     * 内置行为**逐字节一致** —— 升级上来的用户不该因为「多了个节点池」而改变连接目标。
     */
    val builtInNode: RelayNode by unsafeLazy {
        RelayNode(
            id = BUILT_IN_ID,
            host = CdnRelay.HOST,
            port = CdnRelay.PORT,
            secret = CdnRelay.SECRET,
            label = "",
        )
    }

    //<editor-fold desc="设置读取（带缓存）">

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedNodes: List<RelayNode> = emptyList()

    /**
     * 用户自建节点。
     *
     * 按「原始 JSON 字符串」缓存解析结果：`activeNode()` 会在每个被中转的请求上被调用
     * （播放一个视频几十上百次），每次都跑一遍 JSON 解析太浪费；而比较两个短字符串几乎免费。
     */
    fun userNodes(): List<RelayNode> {
        val raw = runCatching { SettingsRepository.relayNodesJson }.getOrDefault("")
        if (raw != cachedRaw) {
            cachedNodes = RelayNode.decodeList(raw)
            cachedRaw = raw
        }
        return cachedNodes
    }

    /** 池子里的全部节点，内置永远第一。 */
    fun allNodes(): List<RelayNode> = listOf(builtInNode) + userNodes()

    private fun manualId(): String =
        runCatching { SettingsRepository.activeRelayNodeId }.getOrDefault("")

    private fun autoSelect(): Boolean =
        runCatching { SettingsRepository.autoSelectRelayNode }.getOrDefault(true)

    //</editor-fold>

    //<editor-fold desc="健康检查与优选">

    private val healthMap = ConcurrentHashMap<String, RelayNodeHealth>()

    /** 某个节点的健康结论；过期视为「没测过」。 */
    fun healthOf(nodeId: String): RelayNodeHealth? =
        healthMap[nodeId]?.takeIf { System.currentTimeMillis() - it.checkedAt < HEALTH_TTL_MS }

    fun allHealth(): Map<String, RelayNodeHealth> = healthMap.toMap()

    /**
     * 当前生效的节点。
     *
     * 决策顺序：
     * 1. 只有内置节点（没加过自建）→ 直接内置，连设置都不读，零开销；
     * 2. 手动模式 → 用手动选的那个；它被删了就退回内置；
     * 3. 自动模式 → 「测过且健康」的里挑延迟最低的；
     * 4. 一个健康的都没有 → 退回手动选的（如果有），否则内置。
     *
     * 第 4 步是故障转移的兜底：所有节点都探测失败时，**必须还有一个去尝试**，
     * 否则「全部不健康」会退化成「完全不中转 = 视频彻底看不了」。
     */
    fun activeNode(): RelayNode {
        val user = userNodes()
        if (user.isEmpty()) return builtInNode
        val all = listOf(builtInNode) + user
        val manual = manualId()
        val manualNode = all.firstOrNull { it.id == manual }

        if (!autoSelect()) return manualNode ?: builtInNode

        val best = all
            .mapNotNull { node -> healthOf(node.id)?.takeIf { it.healthy }?.let { node to it } }
            .minByOrNull { (_, health) -> health.latencyMs }
            ?.first

        return best ?: manualNode ?: builtInNode
    }

    /** 当前生效节点的健康结论（可能为 null = 还没测过 / 已过期）。 */
    val cachedActiveReachable: Boolean?
        get() = healthOf(activeNode().id)?.healthy

    /**
     * 探活一个节点：连打三次 `/ping` 取中位数。
     *
     * 三次是为了抗抖动 —— 单次结果可能因为一个 GC 停顿就差出几百毫秒，
     * 而「选哪台机器」这个决定要稳定。取中位数而不是平均值，是为了不被一次异常值带偏。
     */
    suspend fun check(node: RelayNode): Boolean {
        val samples = withContext(Dispatchers.IO) {
            (0 until 3).map {
                runCatching {
                    val start = System.currentTimeMillis()
                    val request = Request.Builder().url(node.pingUrl).get().build()
                    CdnRelay.probeClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) (System.currentTimeMillis() - start).toInt() else -1
                    }
                }.getOrDefault(-1)
            }
        }
        val ok = samples.filter { it >= 0 }
        val reachable = ok.size >= 2  // 三次里至少两次通，才算这台机器可用
        val latency = if (ok.isEmpty()) -1 else ok.sorted()[ok.size / 2]

        val previous = healthMap[node.id]
        val failures = if (reachable) 0 else (previous?.consecutiveFailures ?: 0) + 1
        healthMap[node.id] = RelayNodeHealth(
            nodeId = node.id,
            // 通了立刻恢复；不通要连续失败到阈值才算不健康 —— 见 FAILURE_THRESHOLD 的注释。
            healthy = reachable || failures < FAILURE_THRESHOLD,
            latencyMs = latency,
            checkedAt = System.currentTimeMillis(),
            consecutiveFailures = failures,
        )
        LogUtil.i(TAG, "节点探活 ${node.displayHost}：${if (reachable) "${latency}ms" else "不可达（连续 $failures 次）"}")
        return reachable
    }

    /**
     * 并联探测全部节点，用于设置页的「节点健康面板」与一键优选。
     *
     * 并发而不是串行：三台机器串行最坏 15 s，用户会以为卡死。
     */
    suspend fun checkAll(): Map<String, RelayNodeHealth> = coroutineScope {
        val nodes = allNodes()
        nodes.map { node -> async { check(node) } }.forEach { it.await() }
        allHealth()
    }

    /** 探活当前生效节点。语义与 8.1 的 `CdnRelay.probe` 完全一致。 */
    suspend fun checkActive(force: Boolean = false): Boolean {
        val node = activeNode()
        if (!force) healthOf(node.id)?.let { return it.healthy }
        return check(node)
    }

    /**
     * 中转请求刚失败时调用。
     *
     * 与 8.1 的 `scheduleReprobe()` 不同之处：这里会**给当前节点记一次失败**，
     * 于是当自动优选开着、而当前节点已经连续失败到阈值时，**下一个请求就会自动换一台**。
     * 这就是「故障转移」：不需要用户做任何事，也不需要重启。
     *
     * 只有一次失败不会立刻切走 —— 否则一次抖动就会来回跳节点，反而更慢。
     */
    fun reportFailure() {
        val node = activeNode()
        val previous = healthMap[node.id]
        val failures = (previous?.consecutiveFailures ?: 0) + 1
        healthMap[node.id] = RelayNodeHealth(
            nodeId = node.id,
            healthy = failures < FAILURE_THRESHOLD,
            latencyMs = -1,
            checkedAt = System.currentTimeMillis(),
            consecutiveFailures = failures,
        )
        if (failures >= FAILURE_THRESHOLD) {
            LogUtil.w(TAG, "节点 ${node.displayHost} 连续失败 $failures 次，移出优选")
        }
    }

    /** 测试用/设置页用：直接写入一条健康结论。 */
    fun putHealth(health: RelayNodeHealth) {
        healthMap[health.nodeId] = health
    }

    //</editor-fold>

    //<editor-fold desc="增删改">

    /** 新增一个自建节点。id 由 [UUID] 生成，避免用户手填冲突。 */
    suspend fun addNode(host: String, port: Int, secret: String, label: String): RelayNodeValidation {
        val normalizedHost = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val normalizedSecret = secret.trim()
        val validation = RelayNode.validate(normalizedHost, port, normalizedSecret)
        if (validation != RelayNodeValidation.Ok) return validation

        val node = RelayNode(
            id = UUID.randomUUID().toString(),
            host = normalizedHost,
            port = port,
            secret = normalizedSecret,
            label = label.trim(),
        )
        // 同 host+port+secret 已在池里（含内置）就不重复添加 —— 加进去只会让人分不清该选哪个。
        if (allNodes().any { it.key == node.key }) return RelayNodeValidation.Duplicate
        val updated = userNodes() + node
        SettingsRepository.setRelayNodesJson(RelayNode.encodeList(updated))
        // 新节点先探一次，免得它要等用户手动点「测速」才参与优选。
        runCatching { check(node) }
        return RelayNodeValidation.Ok
    }

    suspend fun removeNode(id: String) {
        if (id == BUILT_IN_ID) return  // 内置不可删
        val updated = userNodes().filterNot { it.id == id }
        SettingsRepository.setRelayNodesJson(RelayNode.encodeList(updated))
        healthMap.remove(id)
        if (manualId() == id) SettingsRepository.setActiveRelayNodeId("")
    }

    suspend fun renameNode(id: String, label: String) {
        val updated = userNodes().map { if (it.id == id) it.copy(label = label.trim()) else it }
        SettingsRepository.setRelayNodesJson(RelayNode.encodeList(updated))
    }

    /** 手动指定生效节点；传 [BUILT_IN_ID] 之外的空串表示回到自动优选。 */
    suspend fun selectNode(id: String) {
        SettingsRepository.setActiveRelayNodeId(id)
    }

    //</editor-fold>

    /**
     * 节点的本地化显示名：内置节点用资源里的固定文案，自建节点用用户填的 label，
     * 没填 label 就退回 `host:port`。
     */
    fun displayName(node: RelayNode, builtInName: String): String = when {
        node.id == BUILT_IN_ID -> builtInName
        node.label.isNotBlank() -> node.label
        else -> node.displayHost
    }

    /** 供 UI 显示：内置节点名对应的字符串资源。 */
    val builtInNameRes: Int get() = R.string.relay_node_builtin
}
