package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.unsafeLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 诊断项的严重程度。UI 按这个上色，结论也按它汇总。 */
enum class DiagLevel { INFO, PASS, WARN, FAIL }

/**
 * 一条诊断结果。
 *
 * 刻意做成「标题 + 详情 + 建议」三段式：用户真正需要的不是状态码，而是
 * **「现在这样算正常吗」和「我该做什么」**。
 */
data class DiagItem(
    val title: String,
    val level: DiagLevel,
    val detail: String,
    val advice: String? = null,
)

data class DiagReport(
    val items: List<DiagItem>,
    val finished: Boolean,
) {
    val level: DiagLevel
        get() = when {
            items.any { it.level == DiagLevel.FAIL } -> DiagLevel.FAIL
            items.any { it.level == DiagLevel.WARN } -> DiagLevel.WARN
            else -> DiagLevel.PASS
        }

    /** 复制到剪贴板用的纯文本报告。排查问题时直接贴出来就够。 */
    fun toPlainText(): String = buildString {
        appendLine(applicationContext.getString(R.string.diag_title))
        items.forEach { item ->
            append('-').append(item.title).append(": ").append(item.detail)
            item.advice?.let { append("  → ").append(it) }
            appendLine()
        }
    }
}

/**
 * 一键网络诊断。
 *
 * ## 为什么要有这个
 *
 * 这个 App 的网络链路是**五层叠加**的，任何一层出问题，用户看到的都是同一句话
 * 「加载失败」：
 *
 * ```
 * 系统 DNS（会被投毒） → DoH → 内置 Hosts / 自定义镜像
 *      → 代理（SOCKS5 / HTTP） → 被墙 CDN（需要 TLS 中转）
 * ```
 *
 * 靠猜根本定位不到。而这几层**全都能在手机上直接量出来** —— 探活的成本很低，
 * 缺的只是把它们串成一条能自己讲结论的流程。
 *
 * ## 设计
 *
 * 逐项 `emit`，用户能看着结果一条条出来（而不是等一个转圈到结束）。
 * 每项都给出「可操作的建议」，最后再来一条汇总结论。
 */
object NetworkDiagnostics {

    private const val TAG = "NetworkDiagnostics"

    private const val TIMEOUT_MS = 8_000L

    /** 拿来测「直连是否被阻断」的目标：hanime 全部视频与封面都在这上面，且已被实测确认内地 443 被封。 */
    private const val BLOCKED_CDN_URL = "https://vdownload.hembed.com/"

    /**
     * 诊断用 client。
     *
     * 挂 [HDns] 与本工程默认的 `ProxySelector`（启动时已被换成 [HProxySelector]），
     * 所以它走的就是 **App 真实的取数路径**，量出来的结论才对得上首页/详情页的表现。
     */
    private val diagClient by unsafeLazy {
        OkHttpClient.Builder()
            .dns(HDns())
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)
            .build()
    }

    fun run(): Flow<DiagReport> = flow {
        val context = applicationContext
        val items = mutableListOf<DiagItem>()

        suspend fun flush() = emit(DiagReport(items.toList(), finished = false))
        suspend fun add(item: DiagItem) {
            items += item
            flush()
        }

        flush()

        val baseUrl = runCatching { SettingsRepository.baseUrl }.getOrDefault("")
        val host = baseUrl.toHttpUrlOrNull()?.host.orEmpty()

        // ── 1. 系统 DNS ───────────────────────────────────────────────
        val systemIps = if (host.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                withContext(Dispatchers.IO) { Dns.SYSTEM.lookup(host) }
            }.getOrDefault(emptyList()).mapNotNull { it.hostAddress }
        }
        add(
            DiagItem(
                title = context.getString(R.string.diag_dns_system),
                level = if (systemIps.isNotEmpty()) DiagLevel.INFO else DiagLevel.WARN,
                detail = if (systemIps.isNotEmpty()) {
                    context.getString(R.string.diag_dns_ok, systemIps.joinToString(", "))
                } else {
                    context.getString(R.string.diag_dns_fail)
                },
            )
        )

        // ── 2. DoH ───────────────────────────────────────────────────
        val dohOn = runCatching { SettingsRepository.useDoH }.getOrDefault(false)
        val dohIps = if (!dohOn || host.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                withContext(Dispatchers.IO) { HDns().lookupByDoHOnly(host) }
            }.onFailure { LogUtil.w(TAG, "DoH 解析失败：${it.message}") }
                .getOrDefault(emptyList()).mapNotNull { it.hostAddress }
        }
        add(
            DiagItem(
                title = context.getString(R.string.diag_dns_doh),
                level = when {
                    !dohOn -> DiagLevel.INFO
                    dohIps.isNotEmpty() -> DiagLevel.INFO
                    else -> DiagLevel.WARN
                },
                detail = when {
                    !dohOn -> context.getString(R.string.diag_dns_doh_off)
                    dohIps.isNotEmpty() -> context.getString(R.string.diag_dns_ok, dohIps.joinToString(", "))
                    else -> context.getString(R.string.diag_dns_fail)
                },
                advice = if (!dohOn || dohIps.isEmpty()) {
                    context.getString(R.string.diag_dns_doh_advice)
                } else {
                    null
                },
            )
        )

        // ── 3. 镜像可达性 ─────────────────────────────────────────────
        val mirrorStart = System.currentTimeMillis()
        val mirror = runCatching {
            val request = Request.Builder().url(baseUrl).get().build()
            diagClient.newCall(request).execute().use { it.code }
        }
        val mirrorMs = (System.currentTimeMillis() - mirrorStart).toInt()
        val mirrorCode = mirror.getOrNull()
        add(
            DiagItem(
                title = context.getString(R.string.diag_mirror),
                level = if (mirrorCode != null && mirrorCode in 200..399) DiagLevel.PASS else DiagLevel.FAIL,
                detail = if (mirrorCode != null) {
                    context.getString(R.string.diag_mirror_ok, mirrorCode, mirrorMs)
                } else {
                    context.getString(R.string.diag_mirror_fail, mirror.exceptionOrNull()?.message.orEmpty())
                },
                advice = if (mirrorCode == null || mirrorCode !in 200..399) {
                    context.getString(R.string.diag_mirror_advice)
                } else {
                    null
                },
            )
        )

        // ── 4. 视频 CDN 直连 ─────────────────────────────────────────
        //
        // 这一项的期望值要分两种情况说清楚，否则用户会以为「被阻断」是故障：
        // 内地被阻断是**正常现象**（会自动走中转），能直连反而是意外之喜。
        val cdnReachable = runCatching {
            val request = Request.Builder().url(BLOCKED_CDN_URL).get().build()
            diagClient.newCall(request).execute().use { true }
        }.getOrDefault(false)
        add(
            DiagItem(
                title = context.getString(R.string.diag_cdn_direct),
                level = DiagLevel.INFO,
                detail = if (cdnReachable) {
                    context.getString(R.string.diag_cdn_reachable)
                } else {
                    context.getString(R.string.diag_cdn_blocked)
                },
            )
        )

        // ── 5. 中转可用性 ─────────────────────────────────────────────
        val relayEnabled = runCatching { SettingsRepository.allowCdnRelay }.getOrDefault(true)
        val relayStart = System.currentTimeMillis()
        val relayOk = if (relayEnabled) CdnRelay.probe(force = true) else false
        val relayMs = (System.currentTimeMillis() - relayStart).toInt()
        add(
            DiagItem(
                title = context.getString(R.string.diag_relay),
                level = when {
                    !relayEnabled -> DiagLevel.WARN
                    relayOk -> DiagLevel.PASS
                    else -> DiagLevel.FAIL
                },
                detail = when {
                    !relayEnabled -> context.getString(R.string.diag_relay_off)
                    relayOk -> context.getString(R.string.diag_relay_ok, relayMs)
                    else -> context.getString(R.string.diag_relay_fail)
                },
                advice = when {
                    !relayEnabled -> context.getString(R.string.diag_relay_off_advice)
                    relayOk -> null
                    else -> context.getString(R.string.diag_relay_fail_advice)
                },
            )
        )

        // ── 6. 结论 ───────────────────────────────────────────────────
        add(
            DiagItem(
                title = context.getString(R.string.diag_conclusion),
                level = items.maxByOrNull { it.level.ordinal }?.level ?: DiagLevel.PASS,
                detail = when {
                    mirrorCode == null || mirrorCode !in 200..399 ->
                        context.getString(R.string.diag_verdict_browse)
                    relayEnabled && !relayOk ->
                        context.getString(R.string.diag_verdict_video)
                    else -> context.getString(R.string.diag_verdict_ok)
                },
            )
        )

        emit(DiagReport(items.toList(), finished = true))
    }
}
