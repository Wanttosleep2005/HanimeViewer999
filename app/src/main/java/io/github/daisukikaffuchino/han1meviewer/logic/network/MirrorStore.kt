package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.HanimeConstants
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorNode
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorProbe
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 镜像池。
 *
 * ## 为什么需要这一层
 *
 * 在此之前，「选哪个镜像」只有一条路：设置页里那个固定四选一的下拉框，加上一个
 * 「自定义镜像」输入框。三个内置镜像的优先级是**编译期常量**，用户在什么地区、
 * 哪条线路快，代码里没法知道 —— 只能靠他自己一个个点、一个个试。
 *
 * 这一层把「镜像」从常量变成可探测的对象：并发测速、一键选最快的、可加自建镜像。
 * 与 9.0 的中转节点池是同一套思路，但**服务的对象不同** —— 那是链路的出口，
 * 这是站点的入口。
 *
 * ## 与内置常量的关系
 *
 * [HanimeConstants.HANIME_URL] 里的三项**永远**在池子里且不可删：删光自建镜像、
 * 或配置写错后，至少还能回到「出厂可用」的入口。自建镜像只是追加，不替换。
 */
object MirrorStore {

    /** 每个镜像打几次点。取中位数 —— 一次抖动不该决定「哪条线路更快」。 */
    private const val SAMPLE_COUNT = 3

    private const val PREFIX_BUILT_IN = "builtin:"
    private const val PREFIX_CUSTOM = "custom:"

    @Volatile
    private var probeMap: Map<String, MirrorProbe> = emptyMap()

    /** 出厂内置镜像：三个 hanime 镜像 + nJAV。顺序即默认优先级。 */
    fun builtInMirrors(): List<MirrorNode> = listOf(
        MirrorNode(
            id = PREFIX_BUILT_IN + HanimeConstants.HANIME_HOSTNAME[0],
            url = HanimeConstants.HANIME_URL[0],
            builtIn = true,
        ),
        MirrorNode(
            id = PREFIX_BUILT_IN + HanimeConstants.HANIME_HOSTNAME[1],
            url = HanimeConstants.HANIME_URL[1],
            builtIn = true,
        ),
        MirrorNode(
            id = PREFIX_BUILT_IN + HanimeConstants.HANIME_HOSTNAME[2],
            url = HanimeConstants.HANIME_URL[2],
            builtIn = true,
        ),
        // nJAV 不是 hanime 镜像（走独立数据源与解析器），但它同样是一个「入口」，
        // 用户应当能在同一个面板里看到它、测它、切过去。
        MirrorNode(
            id = PREFIX_BUILT_IN + HanimeConstants.NJAV_HOSTNAME,
            url = HanimeConstants.NJAV_URL,
            label = "nJAV",
            builtIn = true,
        ),
    )

    /** 默认镜像（出厂优先项）的 id，UI 用它标「默认」。 */
    val defaultMirrorId: String get() = PREFIX_BUILT_IN + HanimeConstants.HANIME_HOSTNAME[0]

    /** 用户自建镜像。任何解析失败都退化成空列表。 */
    fun extraMirrors(): List<MirrorNode> =
        MirrorNode.decodeList(runCatching { SettingsRepository.extraMirrorsJson }.getOrDefault(""))
            .map { it.copy(id = normalizeId(it.id, it.url), builtIn = false) }

    fun allMirrors(): List<MirrorNode> = builtInMirrors() + extraMirrors()

    // ── 探测 ───────────────────────────────────────────────────────────────

    fun allProbes(): Map<String, MirrorProbe> = probeMap

    fun probeOf(id: String): MirrorProbe? = probeMap[id]

    /**
     * 探测单个镜像。三次取中位数。
     *
     * 用 [ServiceCreator.hClient]（与全应用同一条网络栈：DoH + 代理选择器），
     * 否则测出来的延迟与真实使用完全不是一回事。
     */
    suspend fun probe(mirror: MirrorNode): MirrorProbe = withContext(Dispatchers.IO) {
        val samples = ArrayList<Int>(SAMPLE_COUNT)
        var httpCode = -1
        repeat(SAMPLE_COUNT) {
            val sample = runCatching {
                val start = System.nanoTime()
                val request = Request.Builder().url(mirror.url).get().build()
                ServiceCreator.hClient.newCall(request).execute().use { response ->
                    val ttfb = ((System.nanoTime() - start) / 1_000_000L).toInt()
                    response.code to if (response.isSuccessful) ttfb else null
                }
            }.getOrNull()
            if (sample != null) {
                httpCode = sample.first
                sample.second?.let { samples += it }
            }
        }
        val sorted = samples.sorted()
        val result = MirrorProbe(
            mirrorId = mirror.id,
            reachable = sorted.isNotEmpty(),
            latencyMs = if (sorted.isEmpty()) -1 else sorted[sorted.size / 2],
            httpCode = httpCode,
            checkedAt = System.currentTimeMillis(),
        )
        probeMap = probeMap + (mirror.id to result)
        result
    }

    /** 并发探测全部镜像。串行最坏要等十几秒，用户会以为卡死。 */
    suspend fun probeAll(): Map<String, MirrorProbe> = coroutineScope {
        allMirrors()
            .map { async { probe(it) } }
            .awaitAll()
            .associateBy { it.mirrorId }
    }

    /** 当前已探通的镜像里延迟最低的那个；一个都没测通时返回 `null`。 */
    fun fastest(): MirrorNode? {
        val best = allMirrors()
            .mapNotNull { node -> probeMap[node.id]?.takeIf { it.reachable && it.latencyMs >= 0 }?.let { node to it.latencyMs } }
            .minByOrNull { it.second }
        return best?.first
    }

    /**
     * 当前**生效**的镜像。
     *
     * 判定顺序与 [SettingsRepository.baseUrl] 一致：自定义镜像优先，否则看域名设置。
     * 两者都不匹配任何已知镜像时退回第一个内置项 —— 不能返回 null，调用方到处都要用。
     */
    fun activeMirror(): MirrorNode {
        val all = allMirrors()
        val useCustom = runCatching { SettingsRepository.useCustomMirrorSite }.getOrDefault(false)
        val customUrl = runCatching { SettingsRepository.customMirrorSite }.getOrDefault("")
        if (useCustom && customUrl.isNotBlank()) {
            val root = MirrorNode.normalize(customUrl)
            if (root != null) {
                all.firstOrNull { it.key == root.trimEnd('/') }?.let { return it }
                return MirrorNode(id = customId(root), url = root, builtIn = false)
            }
        }
        val base = runCatching { SettingsRepository.baseUrl }.getOrDefault("")
        val key = base.trimEnd('/')
        return all.firstOrNull { it.key == key } ?: all.first()
    }

    fun activeMirrorId(): String = activeMirror().id

    // ── 增删 ───────────────────────────────────────────────────────────────

    /** 添加自建镜像。返回校验结果；非 [MirrorValidation.Ok] 时什么都不写。 */
    suspend fun addMirror(url: String, label: String): MirrorValidation {
        if (url.isBlank()) return MirrorValidation.Empty
        val normalized = MirrorNode.normalize(url) ?: return MirrorValidation.Invalid
        if (allMirrors().any { it.key == normalized.trimEnd('/') }) return MirrorValidation.Duplicate
        val node = MirrorNode(id = customId(normalized), url = normalized, label = label.trim())
        saveExtra(extraMirrors() + node)
        return MirrorValidation.Ok
    }

    /**
     * 删除自建镜像。内置镜像删不掉（返回 `false`，不写任何东西）。
     *
     * 返回 `true` 表示「删掉的正是当前生效的那个」—— 此时已回退到默认镜像，
     * 调用方需要走一次「重启生效」的提示，否则界面还停在已删除的镜像上。
     */
    suspend fun removeMirror(id: String): Boolean {
        val node = extraMirrors().firstOrNull { it.id == id } ?: return false
        saveExtra(extraMirrors().filterNot { it.id == id })
        probeMap = probeMap - id
        val wasActive = activeMirror().key == node.key
        if (wasActive) {
            // 生效镜像被删 → 回退到出厂默认，避免设置里留着一个已经不存在的地址。
            SettingsRepository.update {
                it.copy(
                    useCustomMirrorSite = false,
                    customMirrorSite = "",
                    domainName = HanimeConstants.HANIME_URL[0],
                    selectedBaseUrl = HanimeConstants.HANIME_URL[0],
                    siteSource = HanimeConstants.siteSourceOf(HanimeConstants.HANIME_URL[0]),
                )
            }
        }
        return wasActive
    }

    /** 供 UI 展示用的名字：优先用户标签，其次主机名。 */
    fun displayName(node: MirrorNode): String =
        node.label.ifBlank { node.displayHost }

    private fun customId(url: String): String = PREFIX_CUSTOM + url.trimEnd('/')

    private suspend fun saveExtra(nodes: List<MirrorNode>) {
        SettingsRepository.update { it.copy(extraMirrorsJson = MirrorNode.encodeList(nodes)) }
    }

    /** 修正历史/脏数据里缺失或重复自建的 id，保证 UI 的 key 稳定。 */
    private fun normalizeId(rawId: String, url: String): String =
        if (rawId.startsWith(PREFIX_CUSTOM)) rawId else customId(url)
}
