package io.github.daisukikaffuchino.han1meviewer.ui.navigation.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.daisukikaffuchino.utils.LogUtil
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daisukikaffuchino.han1meviewer.EMPTY_STRING
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.HealReport
import io.github.daisukikaffuchino.han1meviewer.logic.HealStatus
import io.github.daisukikaffuchino.han1meviewer.logic.HealStepKind
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkSelfHeal
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorNode
import io.github.daisukikaffuchino.han1meviewer.logic.model.MirrorValidation
import io.github.daisukikaffuchino.han1meviewer.logic.model.RelayNodeValidation
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.Parser
import io.github.daisukikaffuchino.han1meviewer.logic.network.DiagReport
import io.github.daisukikaffuchino.han1meviewer.logic.network.DohConfig
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.HanimeNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.network.NetworkDiagnostics
import io.github.daisukikaffuchino.han1meviewer.logic.network.MirrorStore
import io.github.daisukikaffuchino.han1meviewer.logic.network.RelayNodeStore
import io.github.daisukikaffuchino.han1meviewer.logic.network.ServiceCreator
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.logout
import io.github.daisukikaffuchino.han1meviewer.ui.component.ConfirmDialog
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.DelayResultUi
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.DohTestResultUi
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.MirrorActions
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.MirrorUiState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.NetworkSettingsScreen
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.NetworkSettingsUiState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.RelayNodeActions
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.RelayNodeUiState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.SelfHealActions
import io.github.daisukikaffuchino.han1meviewer.ui.screen.settings.SelfHealUiState
import io.github.daisukikaffuchino.utils.ActivityManager
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.SonnerToast
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class DohConflictTarget {
    EnableDoH,
    EnableBuiltInHosts,
}

@Composable
fun NetworkSettingsRouteScreen(embedded: Boolean = false) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
    var currentHost by remember { mutableStateOf(SettingsRepository.baseUrl) }
    var isDelayTesting by remember { mutableStateOf(false) }
    var isDohTesting by remember { mutableStateOf(false) }
    var isCustomMirrorTesting by remember { mutableStateOf(false) }
    var customMirrorTestResult by remember { mutableStateOf<String?>(null) }
    var isDiagnosing by remember { mutableStateOf(false) }
    var diagReport by remember { mutableStateOf<DiagReport?>(null) }
    var diagJob by remember { mutableStateOf<Job?>(null) }
    // ── 中转节点池 ──────────────────────────────────────────────
    var showRelayNodes by remember { mutableStateOf(false) }
    var isRelayNodeTesting by remember { mutableStateOf(false) }
    var relayNodeVersion by remember { mutableIntStateOf(0) }
    var lastNodeValidation by remember { mutableStateOf<RelayNodeValidation?>(null) }
    // ── 镜像池 ──────────────────────────────────────────────────
    var showMirrorPool by remember { mutableStateOf(false) }
    var isMirrorTesting by remember { mutableStateOf(false) }
    var mirrorVersion by remember { mutableIntStateOf(0) }
    var lastMirrorValidation by remember { mutableStateOf<MirrorValidation?>(null) }
    // ── 一键自愈 ────────────────────────────────────────────────
    var showSelfHeal by remember { mutableStateOf(false) }
    var selfHealReport by remember { mutableStateOf<HealReport?>(null) }
    var isSelfHealing by remember { mutableStateOf(false) }
    var selfHealLogVersion by remember { mutableIntStateOf(0) }
    var selfHealJob by remember { mutableStateOf<Job?>(null) }
    var showDomainRestartConfirm by remember { mutableStateOf(false) }
    var showHostsRestartConfirm by remember { mutableStateOf(false) }
    var showCustomHostsValidationError by remember { mutableStateOf<List<String>?>(null) }
    var showCustomMirrorValidationError by remember { mutableStateOf(false) }
    var showCustomMirrorWarningConfirm by remember { mutableStateOf(false) }
    var showDohConflictConfirm by remember { mutableStateOf(false) }
    var showSocksWarning by remember { mutableStateOf(false) }
    var pendingDomainValue by remember { mutableStateOf("") }
    var pendingSiteSource by remember { mutableStateOf<SiteSource?>(null) }
    /** true 表示这次确认框是「切换数据源」触发的，用它决定提示文案。 */
    var pendingSiteSourceSwitch by remember { mutableStateOf(false) }
    var pendingUseCustomMirrorSite by remember { mutableStateOf(SettingsRepository.useCustomMirrorSite) }
    var pendingCustomMirrorSite by remember { mutableStateOf(SettingsRepository.customMirrorSite) }
    var pendingAppendCustomMirrorPath by remember { mutableStateOf(SettingsRepository.appendCustomMirrorPath) }
    var pendingDohConflictTarget by remember { mutableStateOf(DohConflictTarget.EnableDoH) }
    var pendingDohEnabled by remember { mutableStateOf(SettingsRepository.useDoH) }
    var pendingDohPreset by remember { mutableStateOf(SettingsRepository.dohPreset) }
    var pendingDohCustomUrl by remember { mutableStateOf(SettingsRepository.dohCustomUrl) }
    var pendingDohBootstrapIps by remember { mutableStateOf(SettingsRepository.dohBootstrapIps) }
    var pendingDohTimeoutSeconds by remember { mutableIntStateOf(SettingsRepository.dohTimeoutSeconds) }
    val delayResults = remember { mutableStateListOf<DelayResultUi>() }
    val dohTestResults = remember { mutableStateListOf<DohTestResultUi>() }
    val delayHandler = remember { Handler(Looper.getMainLooper()) }
    val dohHandler = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newCachedThreadPool() }
    val uiState = remember(settings, context) { buildNetworkSettingsUiState(context) }
    val networkTimeoutText = stringResource(R.string.network_timeout_text)
    val customMirrorInvalidText = stringResource(R.string.custom_mirror_site_invalid)
    val customMirrorTestingText = stringResource(R.string.custom_mirror_site_testing)
    fun stopDelayTest() {
        isDelayTesting = false
        delayHandler.removeCallbacksAndMessages(null)
    }

    fun stopDohTest() {
        isDohTesting = false
        dohHandler.removeCallbacksAndMessages(null)
    }

    fun measureDelay(ip: String): Int {
        return try {
            val start = System.currentTimeMillis()
            val address = InetAddress.getByName(ip)
            val reachable = address.isReachable(2000)
            if (reachable) (System.currentTimeMillis() - start).toInt() else -1
        } catch (_: Exception) {
            -1
        }
    }

    fun testIp(ip: String) {
        if (!isDelayTesting) return
        executor.execute {
            val delay = measureDelay(ip)
            delayHandler.post {
                val index = delayResults.indexOfFirst { it.ip == ip }
                if (index >= 0) {
                    delayResults[index] = DelayResultUi(ip, delay)
                }
            }
        }
    }

    fun scheduleNextTest(ipList: List<String>) {
        if (!isDelayTesting) return
        ipList.forEach(::testIp)
        delayHandler.postDelayed({ scheduleNextTest(ipList) }, 2000)
    }

    /**
     * 一键网络诊断。
     *
     * 逐项收集结果，所以用户能看着结论一条条冒出来；关掉弹窗要**取消**协程，
     * 否则跑完时又会把已关闭的弹窗顶回来。
     */
    fun runDiagnostics() {
        diagJob?.cancel()
        diagReport = null
        isDiagnosing = true
        diagJob = coroutineScope.launch {
            runCatching {
                NetworkDiagnostics.run().collect { diagReport = it }
            }.onFailure { LogUtil.w("NET_DIAG", "diagnose failed: ${it.message}") }
            isDiagnosing = false
        }
    }

    fun runDohTest() {
        if (isDohTesting) return
        val host = SettingsRepository.baseUrl.toUri().host ?: applicationContext.getString(R.string.unknow)
        currentHost = SettingsRepository.baseUrl
        dohTestResults.clear()
        isDohTesting = true
        executor.execute {
            val start = System.currentTimeMillis()
            val result = runCatching { HDns().lookupByDoHOnly(host) }
            val delay = (System.currentTimeMillis() - start).toInt()
            dohHandler.post {
                dohTestResults.clear()
                result.onSuccess { list ->
                    dohTestResults.add(
                        DohTestResultUi(
                            host = host,
                            ips = list.mapNotNull { it.hostAddress }.distinct(),
                            delay = delay,
                            message = "",
                        )
                    )
                }.onFailure { throwable ->
                    LogUtil.w("DOH_TEST", "lookup failed for $host: ${throwable.message}")
                    dohTestResults.add(
                        DohTestResultUi(
                            host = host,
                            ips = emptyList(),
                            delay = -1,
                            message = throwable.message?.ifBlank { networkTimeoutText }
                                ?: networkTimeoutText,
                        )
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopDelayTest()
            stopDohTest()
            diagJob?.cancel()
            selfHealJob?.cancel()
            executor.shutdownNow()
        }
    }

    /**
     * 节点池的 UI 状态。
     *
     * [RelayNodeStore] 是普通 object 而不是 Flow（它要在 OkHttp 拦截器的热路径上被同步读取），
     * 所以这里靠 [relayNodeVersion] 这个「手动版本号」在增删/测速后触发重算。
     */
    val relayNodeUi = remember(settings, relayNodeVersion, isRelayNodeTesting, lastNodeValidation, context) {
        RelayNodeUiState(
            nodes = RelayNodeStore.allNodes(),
            health = RelayNodeStore.allHealth(),
            activeNodeId = RelayNodeStore.activeNode().id,
            autoSelect = SettingsRepository.autoSelectRelayNode,
            testing = isRelayNodeTesting,
            lastValidation = lastNodeValidation,
            builtInName = context.getString(R.string.relay_node_builtin),
        )
    }

    val relayNodeActions = RelayNodeActions(
        onTest = {
            if (!isRelayNodeTesting) {
                isRelayNodeTesting = true
                coroutineScope.launch {
                    runCatching { RelayNodeStore.checkAll() }
                        .onFailure { LogUtil.w("NET_DIAG", "节点测速失败：${it.message}") }
                    isRelayNodeTesting = false
                    relayNodeVersion++
                }
            }
        },
        onSelect = { id ->
            coroutineScope.launch {
                // 手动选了某台就把自动优选关掉 —— 否则「选了却没生效」会让人莫名其妙。
                RelayNodeStore.selectNode(id)
                SettingsRepository.setAutoSelectRelayNode(false)
                relayNodeVersion++
            }
        },
        onAutoSelectChange = { enabled ->
            coroutineScope.launch {
                SettingsRepository.setAutoSelectRelayNode(enabled)
                relayNodeVersion++
            }
        },
        onAdd = { host, port, secret, label ->
            coroutineScope.launch {
                val result = RelayNodeStore.addNode(
                    host = host,
                    port = port.toIntOrNull() ?: -1,
                    secret = secret,
                    label = label,
                )
                lastNodeValidation = result
                relayNodeVersion++
            }
        },
        onRemove = { id ->
            coroutineScope.launch {
                RelayNodeStore.removeNode(id)
                relayNodeVersion++
            }
        },
        onDismiss = {
            showRelayNodes = false
            lastNodeValidation = null
        },
    )

    /**
     * 镜像池的 UI 状态。
     *
     * 与节点池同理：[MirrorStore] 是 object 而非 Flow，靠 [mirrorVersion] 手动触发重算。
     */
    val mirrorUi = remember(settings, mirrorVersion, isMirrorTesting, lastMirrorValidation) {
        MirrorUiState(
            mirrors = MirrorStore.allMirrors(),
            probes = MirrorStore.allProbes(),
            activeMirrorId = MirrorStore.activeMirrorId(),
            defaultMirrorId = MirrorStore.defaultMirrorId,
            testing = isMirrorTesting,
            lastValidation = lastMirrorValidation,
        )
    }

    // 把「切到某个镜像」落成一次待确认的域名切换，复用既有的重启流程：
    // 内置镜像直接进重启确认；自建镜像先过一遍「自定义镜像」警告。
    fun stageMirrorSwitch(node: MirrorNode) {
        pendingDomainValue = node.url
        pendingSiteSource = HanimeConstants.siteSourceOf(node.url)
        pendingSiteSourceSwitch = false
        if (node.builtIn) {
            pendingUseCustomMirrorSite = false
            pendingCustomMirrorSite = SettingsRepository.customMirrorSite
            pendingAppendCustomMirrorPath = SettingsRepository.appendCustomMirrorPath
            showDomainRestartConfirm = true
        } else {
            pendingUseCustomMirrorSite = true
            pendingCustomMirrorSite = node.url
            pendingAppendCustomMirrorPath = false
            showCustomMirrorWarningConfirm = true
        }
    }

    val mirrorActions = MirrorActions(
        onTest = {
            if (!isMirrorTesting) {
                isMirrorTesting = true
                coroutineScope.launch {
                    runCatching { MirrorStore.probeAll() }
                        .onFailure { LogUtil.w("NET_DIAG", "镜像测速失败：${it.message}") }
                    isMirrorTesting = false
                    mirrorVersion++
                }
            }
        },
        onUseFastest = {
            val fastest = MirrorStore.fastest()
            if (fastest == null) {
                SonnerToast.warning(R.string.mirror_no_fastest)
            } else {
                showMirrorPool = false
                stageMirrorSwitch(fastest)
            }
        },
        onSelect = { id ->
            MirrorStore.allMirrors().firstOrNull { it.id == id }?.let { node ->
                showMirrorPool = false
                stageMirrorSwitch(node)
            }
        },
        onAdd = { url, label ->
            coroutineScope.launch {
                val result = MirrorStore.addMirror(url, label)
                lastMirrorValidation = result
                if (result == MirrorValidation.Ok) SonnerToast.success(R.string.mirror_added)
                mirrorVersion++
            }
        },
        onRemove = { id ->
            coroutineScope.launch {
                val wasActive = MirrorStore.removeMirror(id)
                if (wasActive) {
                    SonnerToast.warning(R.string.mirror_removed_active)
                    showMirrorPool = false
                    showDomainRestartConfirm = true
                } else {
                    SonnerToast.success(R.string.mirror_removed)
                }
                mirrorVersion++
            }
        },
        onDismiss = {
            showMirrorPool = false
            lastMirrorValidation = null
        },
    )

    /**
     * 一键自愈的界面状态。
     *
     * 「是否需要重启」不看 [HealReport.changedCount]，只看**镜像那一步是否真的改了**：
     * 换了中转节点是热生效的，不需要重启；只有换镜像才要。
     */
    val selfHealUi = remember(settings, selfHealReport, isSelfHealing, selfHealLogVersion) {
        val report = selfHealReport
        SelfHealUiState(
            report = report,
            running = isSelfHealing,
            log = NetworkSelfHeal.log(),
            needsRestart = report?.finished == true && report.steps.any {
                it.kind == HealStepKind.MirrorApply && it.status == HealStatus.Changed
            },
        )
    }

    val selfHealActions = SelfHealActions(
        onRun = {
            if (!isSelfHealing) {
                selfHealJob?.cancel()
                selfHealReport = null
                isSelfHealing = true
                selfHealJob = coroutineScope.launch {
                    try {
                        NetworkSelfHeal.run(context.getString(R.string.relay_node_builtin))
                            .collect { selfHealReport = it }
                        // 自愈可能动了镜像/节点，两个池子的测速结论与生效项都变了，
                        // 让面板重算，免得回到设置页还显示旧状态。
                        selfHealLogVersion++
                        mirrorVersion++
                        relayNodeVersion++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        LogUtil.w("NET_HEAL", "自愈失败：${t.message}")
                    } finally {
                        isSelfHealing = false
                    }
                }
            }
        },
        onRestart = {
            // 设置已经由 NetworkSelfHeal 落库，这里复用「域名切换 → 重启」那条既有流程：
            // 待确认值留空 = 保持当前值，只借用它的确认框与 logout+restart。
            showSelfHeal = false
            pendingDomainValue = ""
            pendingSiteSource = null
            pendingSiteSourceSwitch = false
            pendingUseCustomMirrorSite = SettingsRepository.useCustomMirrorSite
            pendingCustomMirrorSite = SettingsRepository.customMirrorSite
            pendingAppendCustomMirrorPath = SettingsRepository.appendCustomMirrorPath
            showDomainRestartConfirm = true
        },
        onClearLog = {
            coroutineScope.launch {
                NetworkSelfHeal.clearLog()
                selfHealLogVersion++
                SonnerToast.success(R.string.self_heal_log_cleared)
            }
        },
        onDismiss = {
            selfHealJob?.cancel()
            selfHealJob = null
            isSelfHealing = false
            // 半截的报告没有结论、也没有配色依据，留着只会让人以为界面卡住了。
            if (selfHealReport?.finished != true) selfHealReport = null
            showSelfHeal = false
        },
    )

    NetworkSettingsScreen(
        state = uiState,
        domainOptions = buildDomainOptions(context),
        currentHost = currentHost,
        delayResults = delayResults,
        dohTestResults = dohTestResults,
        isDelayTesting = isDelayTesting,
        isDohTesting = isDohTesting,
        proxyType = SettingsRepository.proxyType,
        proxyIp = SettingsRepository.proxyIp,
        proxyPort = SettingsRepository.proxyPort,
        proxyUsername = SettingsRepository.proxyUsername,
        proxyPassword = SettingsRepository.proxyPassword,
        dohEnabled = SettingsRepository.useDoH,
        dohPreset = SettingsRepository.dohPreset,
        dohCustomUrl = SettingsRepository.dohCustomUrl,
        dohBootstrapIps = SettingsRepository.dohBootstrapIps,
        dohTimeoutSeconds = SettingsRepository.dohTimeoutSeconds,
        useCustomMirrorSite = SettingsRepository.useCustomMirrorSite,
        customMirrorSite = SettingsRepository.customMirrorSite,
        appendCustomMirrorPath = SettingsRepository.appendCustomMirrorPath,
        customMirrorTestResult = customMirrorTestResult,
        isCustomMirrorTesting = isCustomMirrorTesting,
        onDomainChange = { newValue ->
            val origin = SettingsRepository.baseUrl
            if (newValue != origin) {
                pendingDomainValue = newValue
                // 域名和数据源必须一起改：选到 njavtv.com 就得把数据源切成 nJAV，
                // 选回任意 hanime 镜像则切回 hanime1 —— 否则会出现
                // 「数据源写着 nJAV，域名却还是 hanime1.me」这种自相矛盾的状态。
                pendingSiteSource = HanimeConstants.siteSourceOf(newValue)
                pendingSiteSourceSwitch = false
                pendingUseCustomMirrorSite = false
                pendingCustomMirrorSite = SettingsRepository.customMirrorSite
                pendingAppendCustomMirrorPath = SettingsRepository.appendCustomMirrorPath
                showDomainRestartConfirm = true
            }
        },
        siteSource = settings.siteSource.value,
        onSiteSourceChange = { newValue ->
            val source = SiteSource.fromValue(newValue)
            if (source != settings.siteSource) {
                // ⚠️ 光写 siteSource 是不够的：首页 / 搜索页的 ViewModel 早就把旧站点的
                // 数据缓存住了，用户切完看不到任何变化，会以为「这一项点不动」。
                // 所以和数据源配套把域名一起改掉，然后走统一的「重启应用」流程，
                // 保证切换结果肉眼可见。
                pendingSiteSource = source
                pendingSiteSourceSwitch = true
                pendingDomainValue = if (source.isNjav) {
                    HanimeConstants.NJAV_URL
                } else {
                    SettingsRepository.selectedBaseUrl
                        .takeIf { it.isNotBlank() && it in HanimeConstants.HANIME_URL }
                        ?: HanimeConstants.HANIME_URL[0]
                }
                pendingUseCustomMirrorSite = false
                pendingCustomMirrorSite = SettingsRepository.customMirrorSite
                pendingAppendCustomMirrorPath = SettingsRepository.appendCustomMirrorPath
                showDomainRestartConfirm = true
            }
        },
        onSaveCustomMirrorSite = { enabled, url, appendPath ->
            val normalizedUrl = normalizeCustomMirrorSite(url)
            if (enabled && normalizedUrl == null) {
                showCustomMirrorValidationError = true
                return@NetworkSettingsScreen
            }
            val customMirrorSite = normalizedUrl.orEmpty()
            if (enabled != SettingsRepository.useCustomMirrorSite ||
                customMirrorSite != SettingsRepository.customMirrorSite ||
                appendPath != SettingsRepository.appendCustomMirrorPath
            ) {
                pendingUseCustomMirrorSite = enabled
                pendingCustomMirrorSite = customMirrorSite
                pendingAppendCustomMirrorPath = appendPath
                if (enabled) {
                    showCustomMirrorWarningConfirm = true
                } else {
                    showDomainRestartConfirm = true
                }
            }
        },
        onTestCustomMirrorSite = { url, appendPath ->
            val normalizedUrl = normalizeCustomMirrorSite(url)
            if (normalizedUrl == null) {
                customMirrorTestResult = customMirrorInvalidText
                return@NetworkSettingsScreen
            }
            if (isCustomMirrorTesting) return@NetworkSettingsScreen
            isCustomMirrorTesting = true
            customMirrorTestResult = customMirrorTestingText
            executor.execute {
                val result = testCustomMirrorSite(context, normalizedUrl, appendPath)
                Handler(Looper.getMainLooper()).post {
                    customMirrorTestResult = result
                    isCustomMirrorTesting = false
                }
            }
        },
        onUseBuiltInHostsChange = { value ->
            if (value && SettingsRepository.useDoH) {
                showDohConflictConfirm = true
                pendingDohConflictTarget = DohConflictTarget.EnableBuiltInHosts
                return@NetworkSettingsScreen
            }
            coroutineScope.launch {
                SettingsRepository.update { it.copy(useBuiltInHosts = value) }
                showHostsRestartConfirm = true
            }
        },
        onAllowImageRelayChange = { value ->
            coroutineScope.launch {
                SettingsRepository.update { it.copy(allowImageRelay = value) }
            }
        },
        onAllowCdnRelayChange = { value ->
            coroutineScope.launch {
                SettingsRepository.update { it.copy(allowCdnRelay = value) }
            }
        },
        onSaveCustomHosts = { data ->
            val errors = HDns.validateCustomHosts(data)
            if (errors.isNotEmpty()) {
                showCustomHostsValidationError = errors
                return@NetworkSettingsScreen
            }
            coroutineScope.launch {
                SettingsRepository.update { it.copy(customHostsData = data) }
                if (SettingsRepository.useBuiltInHosts) HanimeNetwork.rebuildNetwork()
            }
        },
        customHostsData = SettingsRepository.customHostsData,
        onSaveDohSettings = { enabled, preset, url, bootstrapIps, timeoutSeconds ->
            pendingDohEnabled = enabled
            pendingDohPreset = preset
            pendingDohCustomUrl = url
            pendingDohBootstrapIps = bootstrapIps
            pendingDohTimeoutSeconds = timeoutSeconds
            if (enabled && SettingsRepository.useBuiltInHosts) {
                showDohConflictConfirm = true
                pendingDohConflictTarget = DohConflictTarget.EnableDoH
                return@NetworkSettingsScreen
            }
            coroutineScope.launch {
                SettingsRepository.update { it.copy(useDoH = enabled, dohPreset = preset, dohCustomUrl = url, dohBootstrapIps = bootstrapIps, dohTimeoutSeconds = timeoutSeconds.coerceIn(1, 60)) }
                currentHost = SettingsRepository.baseUrl
                HanimeNetwork.rebuildNetwork()
            }
        },
        diagReport = diagReport,
        isDiagnosing = isDiagnosing,
        onOpenDiagnostics = { runDiagnostics() },
        onDismissDiagnostics = {
            diagJob?.cancel()
            diagJob = null
            isDiagnosing = false
            diagReport = null
        },
        onCopyDiagReport = { report ->
            val text = report.toPlainText()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("network-diag", text))
            SonnerToast.success(R.string.diag_copied)
        },
        showRelayNodes = showRelayNodes,
        relayNodeUi = relayNodeUi,
        relayNodeActions = relayNodeActions,
        showMirrorPool = showMirrorPool,
        mirrorUi = mirrorUi,
        mirrorActions = mirrorActions,
        showSelfHeal = showSelfHeal,
        selfHealUi = selfHealUi,
        selfHealActions = selfHealActions,
        onOpenSelfHeal = { showSelfHeal = true },
        onOpenMirrorPool = {
            showMirrorPool = true
            lastMirrorValidation = null
            // 打开就顺手测一次，别让面板先显示一排「未测速」。
            if (MirrorStore.allProbes().isEmpty() && !isMirrorTesting) {
                isMirrorTesting = true
                coroutineScope.launch {
                    runCatching { MirrorStore.probeAll() }
                        .onFailure { LogUtil.w("NET_DIAG", "镜像测速失败：${it.message}") }
                    isMirrorTesting = false
                    mirrorVersion++
                }
            }
        },
        onOpenRelayNodes = {
            showRelayNodes = true
            lastNodeValidation = null
            // 打开就顺手测一次：让面板一上来就有数据，而不是先显示一排「未测速」。
            if (RelayNodeStore.allHealth().isEmpty() && !isRelayNodeTesting) {
                isRelayNodeTesting = true
                coroutineScope.launch {
                    runCatching { RelayNodeStore.checkAll() }
                    isRelayNodeTesting = false
                    relayNodeVersion++
                }
            }
        },
        onOpenDelayTest = {
            val host =
                SettingsRepository.baseUrl.toUri().host ?: applicationContext.getString(R.string.unknow)
            currentHost = SettingsRepository.baseUrl
            delayResults.clear()
            isDelayTesting = true
            executor.execute {
                val ipList = HDns().getCDNList(host)
                Handler(Looper.getMainLooper()).post {
                    LogUtil.i("delayTest", ipList.toString())
                    delayResults.clear()
                    delayResults.addAll(ipList.map { DelayResultUi(it, -1) })
                    scheduleNextTest(ipList)
                }
            }
        },
        onOpenDohTest = { runDohTest() },
        onDismissDelayTest = { stopDelayTest() },
        onDismissDohTest = { stopDohTest() },
        onApplyProxy = { config ->
            val type = config.type
            val ip = config.ip
            val port = config.port
            val valid = when (type) {
                HProxySelector.TYPE_DIRECT, HProxySelector.TYPE_SYSTEM -> true
                HProxySelector.TYPE_HTTP, HProxySelector.TYPE_SOCKS -> HProxySelector.validateIp(ip) && HProxySelector.validatePort(
                    port
                )

                else -> false
            }
            if (!valid) {
                SonnerToast.warning(R.string.invalid_ip_or_port)
                return@NetworkSettingsScreen
            }
            // 密码可以留空（有些代理只校验用户名），但用户名留空 = 匿名代理，
            // 密码就一并清掉，避免留下一个「有密码却没用户名」的死配置。
            val username = config.username
            val password = if (username.isBlank()) "" else config.password
            if (type == HProxySelector.TYPE_SOCKS) {
                showSocksWarning = true
            }
            coroutineScope.launch {
                SettingsRepository.update {
                    it.copy(
                        proxyType = io.github.daisukikaffuchino.han1meviewer.logic.model.ProxyType.fromId(type),
                        proxyIp = ip,
                        proxyPort = port,
                        proxyUsername = username,
                        proxyPassword = password,
                    )
                }
                HProxySelector.rebuildNetwork()
                HanimeNetwork.rebuildNetwork()
            }
        },
        embedded = embedded,
    )

    ConfirmDialog(
        visible = showDomainRestartConfirm,
        title = stringResource(R.string.attention),
        // 数据源切换与域名切换共用同一个「重启」确认框，只是文案不同。
        message = if (pendingSiteSourceSwitch) {
            stringResource(R.string.site_source_restart_confirm)
        } else {
            stringResource(R.string.domain_change_tips).trimIndent()
        },
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        cancelable = false,
        onConfirm = {
            coroutineScope.launch {
                SettingsRepository.update {
                    it.copy(
                        domainName = pendingDomainValue.ifEmpty { it.domainName },
                        selectedBaseUrl = pendingDomainValue.ifEmpty { it.selectedBaseUrl },
                        // 数据源必须在这里落库，否则「数据源」那一项点完重启回来还是旧值，
                        // 用户会以为它点不动 —— 这正是 mod.5 里 nJAV 不可选的根因之一。
                        siteSource = pendingSiteSource ?: it.siteSource,
                        useCustomMirrorSite = pendingUseCustomMirrorSite,
                        customMirrorSite = pendingCustomMirrorSite,
                        appendCustomMirrorPath = pendingAppendCustomMirrorPath,
                    )
                }
                logout()
                ActivityManager.restart(killProcess = true)
            }
        },
        onDismiss = {
            pendingDomainValue = ""
            pendingSiteSource = null
            pendingSiteSourceSwitch = false
            pendingUseCustomMirrorSite = SettingsRepository.useCustomMirrorSite
            pendingCustomMirrorSite = SettingsRepository.customMirrorSite
            pendingAppendCustomMirrorPath = SettingsRepository.appendCustomMirrorPath
            showDomainRestartConfirm = false
        },
    )

    if (showCustomMirrorValidationError) {
        AlertDialog(
            onDismissRequest = { showCustomMirrorValidationError = false },
            title = { Text(stringResource(R.string.attention)) },
            text = { Text(stringResource(R.string.custom_mirror_site_invalid)) },
            confirmButton = {
                TextButton(onClick = { showCustomMirrorValidationError = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    ConfirmDialog(
        visible = showHostsRestartConfirm,
        title = stringResource(R.string.attention),
        message = stringResource(R.string.restart_or_not_working, EMPTY_STRING),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        cancelable = false,
        onConfirm = { ActivityManager.restart(killProcess = true) },
        onDismiss = { showHostsRestartConfirm = false },
    )

    val validationErrors = showCustomHostsValidationError
    if (validationErrors != null) {
        AlertDialog(
            onDismissRequest = { showCustomHostsValidationError = null },
            title = { Text(stringResource(R.string.attention)) },
            text = { Text(validationErrors.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { showCustomHostsValidationError = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    ConfirmDialog(
        visible = showCustomMirrorWarningConfirm,
        title = stringResource(R.string.attention),
        message = stringResource(R.string.custom_mirror_site_warning),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        cancelable = false,
        onConfirm = {
            showCustomMirrorWarningConfirm = false
            showDomainRestartConfirm = true
        },
        onDismiss = {
            pendingUseCustomMirrorSite = SettingsRepository.useCustomMirrorSite
            pendingCustomMirrorSite = SettingsRepository.customMirrorSite
            pendingAppendCustomMirrorPath = SettingsRepository.appendCustomMirrorPath
            showCustomMirrorWarningConfirm = false
        },
    )

    ConfirmDialog(
        visible = showDohConflictConfirm,
        title = stringResource(R.string.attention),
        message = stringResource(R.string.doh_conflict_message),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        cancelable = false,
        onConfirm = {
            coroutineScope.launch {
                SettingsRepository.update {
                    when (pendingDohConflictTarget) {
                        DohConflictTarget.EnableDoH -> it.copy(useBuiltInHosts = false, useDoH = pendingDohEnabled, dohPreset = pendingDohPreset, dohCustomUrl = pendingDohCustomUrl, dohBootstrapIps = pendingDohBootstrapIps, dohTimeoutSeconds = pendingDohTimeoutSeconds.coerceIn(1, 60))
                        DohConflictTarget.EnableBuiltInHosts -> it.copy(useDoH = false, useBuiltInHosts = true)
                    }
                }
                showDohConflictConfirm = false
                HanimeNetwork.rebuildNetwork()
            }
        },
        onDismiss = { showDohConflictConfirm = false },
    )

    ConfirmDialog(
        visible = showSocksWarning,
        title = stringResource(R.string.warning),
        message = stringResource(R.string.mpv_socks5_warning),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = { showSocksWarning = false },
        onDismiss = { showSocksWarning = false },
    )
}

private fun buildNetworkSettingsUiState(context: Context): NetworkSettingsUiState {
    return NetworkSettingsUiState(
        domainName = SettingsRepository.baseUrl,
        domainDisplay = buildDomainOptions(context).firstOrNull { it.second == SettingsRepository.baseUrl }?.first
            ?: SettingsRepository.baseUrl,
        proxySummary = when (SettingsRepository.proxyType) {
            HProxySelector.TYPE_DIRECT -> context.getString(R.string.direct)
            HProxySelector.TYPE_SYSTEM -> context.getString(R.string.system_proxy)
            HProxySelector.TYPE_HTTP -> context.getString(
                R.string.http_proxy,
                SettingsRepository.proxyIp,
                SettingsRepository.proxyPort
            )

            HProxySelector.TYPE_SOCKS -> context.getString(
                R.string.socks_proxy,
                SettingsRepository.proxyIp,
                SettingsRepository.proxyPort
            )

            else -> context.getString(R.string.direct)
        },
        useBuiltInHosts = SettingsRepository.useBuiltInHosts,
        useCustomMirrorSite = SettingsRepository.useCustomMirrorSite,
        customMirrorSite = SettingsRepository.customMirrorSite,
        appendCustomMirrorPath = SettingsRepository.appendCustomMirrorPath,
        useDoH = SettingsRepository.useDoH,
        dohSummary = buildDohSummary(context),
        delaySummary = context.getString(R.string.node_latency_sum),
        allowImageRelay = SettingsRepository.allowImageRelay,
        allowCdnRelay = SettingsRepository.allowCdnRelay,
    )
}

private fun normalizeCustomMirrorSite(url: String): String? {
    val trimmed = url.trim().trimEnd('/')
    val uri = runCatching { trimmed.toUri() }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
    if (!uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) return null
    return url.trim()
}

private fun testCustomMirrorSite(context: Context, homeUrl: String, appendPath: Boolean): String {
    return runCatching {
        val request = Request.Builder().url(homeUrl).get().build()
        ServiceCreator.hClient.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val body = response.body.string()
            if (!response.isSuccessful) {
                return context.getString(
                    R.string.custom_mirror_site_test_failed_http,
                    response.code,
                    finalUrl,
                )
            }

            val apiBaseUrl = buildCustomMirrorApiBaseUrl(homeUrl, appendPath)
            val watchTestResult = testCustomMirrorWatchUrl(context, apiBaseUrl)
            when (val parseResult = Parser.homePageVer2(body)) {
                is WebsiteState.Success -> if (watchTestResult == null) {
                    context.getString(
                        R.string.custom_mirror_site_test_success,
                        finalUrl,
                        apiBaseUrl,
                    )
                } else {
                    context.getString(
                        R.string.custom_mirror_site_test_partial_success,
                        finalUrl,
                        apiBaseUrl,
                        watchTestResult,
                    )
                }

                is WebsiteState.Error -> context.getString(
                    R.string.custom_mirror_site_test_parse_failed,
                    finalUrl,
                    parseResult.throwable.message ?: parseResult.throwable::class.java.simpleName,
                )

                WebsiteState.Loading -> context.getString(
                    R.string.custom_mirror_site_test_parse_failed,
                    finalUrl,
                    context.getString(R.string.loading),
                )
            }
        }
    }.getOrElse { throwable ->
        context.getString(
            R.string.custom_mirror_site_test_failed,
            throwable.message ?: throwable::class.java.simpleName,
        )
    }
}

private fun testCustomMirrorWatchUrl(context: Context, apiBaseUrl: String): String? {
    return runCatching {
        val url = apiBaseUrl + "search"
        val request = Request.Builder().url(url).get().build()
        ServiceCreator.hClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                null
            } else {
                context.getString(
                    R.string.custom_mirror_site_watch_test_failed_http,
                    response.code,
                    response.request.url.toString(),
                )
            }
        }
    }.getOrElse { throwable ->
        context.getString(
            R.string.custom_mirror_site_watch_test_failed,
            throwable.message ?: throwable::class.java.simpleName,
        )
    }
}

private fun buildCustomMirrorApiBaseUrl(homeUrl: String, appendPath: Boolean): String {
    val url = if (appendPath) homeUrl else {
        val uri = homeUrl.toUri()
        "${uri.scheme}://${uri.encodedAuthority}"
    }
    return if (url.endsWith('/')) url else "$url/"
}

private fun buildDohSummary(context: Context): String {
    if (!SettingsRepository.useDoH) return context.getString(R.string.doh_disabled_summary)
    if (SettingsRepository.useBuiltInHosts) return context.getString(R.string.doh_conflict_message)
    val core = if (SettingsRepository.dohPreset == "custom") {
        SettingsRepository.dohCustomUrl.ifBlank { context.getString(R.string.custom) }
    } else {
        DohConfig.selectedPreset().title
    }
    val bootstrap = DohConfig.bootstrapIps().takeIf { it.isNotEmpty() }?.joinToString()
    return if (bootstrap != null) "$core\nBootstrap: $bootstrap" else core
}
