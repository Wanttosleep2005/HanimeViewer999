package io.github.daisukikaffuchino.han1meviewer.ui.activity

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.daisukikaffuchino.utils.LogUtil
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants.ANIME_URL
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants.HANIME_URL
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logout
import io.github.daisukikaffuchino.han1meviewer.ui.bridge.VideoPageHost
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.AccountRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.HanimeScreen
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.LoginRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.TopLevelBackStack
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.VideoRoute
import io.github.daisukikaffuchino.han1meviewer.ui.screen.main.MainActivityContent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.HomePageViewModel
import io.github.daisukikaffuchino.utils.ActivityManager
import io.github.daisukikaffuchino.utils.isX86_64Device
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    val viewModel by viewModels<HomePageViewModel>()

    val mainBackStack: TopLevelBackStack<HanimeScreen>
        get() = viewModel.mainBackStack
    private var showAuthGuard by mutableStateOf(true)
    private val pendingNavigationRequests = MutableSharedFlow<Intent>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    private var currentVideoHost: VideoPageHost? = null
    private var showSiteSwitchConfirm by mutableStateOf(false)
    private var logoutDialogCloseCurrentPage by mutableStateOf<Boolean?>(null)

    companion object {
        const val ACTION_TOGGLE_PLAY = "io.github.daisukikaffuchino.han1meviewer.ACTION_TOGGLE_PLAY"
    }

    private var hasAuthenticated = false
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LogUtil.i("pipmode", "✅ onReceive called with action: ${intent?.action}")
            when (intent?.action) {
                ACTION_TOGGLE_PLAY -> {
                    LogUtil.i("pipmode", "🎬 ACTION_TOGGLE_PLAY triggered")
                    togglePlayPause()
                }
            }
        }
    }

    private fun initData() {
        setHanimeContent {
            MainActivityContent(
                activity = this,
                viewModel = viewModel,
                pendingNavigationRequests = pendingNavigationRequests,
                showAuthGuard = showAuthGuard,
                onOpenAccount = { mainBackStack.add(AccountRoute) },
                showSiteSwitchConfirm = showSiteSwitchConfirm,
                logoutDialogCloseCurrentPage = logoutDialogCloseCurrentPage,
                onLogoutClick = { showLogoutConfirmDialog() },
                onRequireLogin = { openLogin() },
                onSwitchSiteClick = { showSiteSwitchConfirm = true },
                onDismissSiteSwitch = { showSiteSwitchConfirm = false },
                onConfirmSiteSwitch = ::confirmSiteSwitch,
                onDismissLogout = { logoutDialogCloseCurrentPage = null },
                onConfirmLogout = ::confirmLogout,
                onOpenClipboardVideo = ::showVideoDetailFragment,
            )
        }
    }

    override fun beforeSuperOnCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installSplashScreen().apply {
                setKeepOnScreenCondition { !hasAuthenticated }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val useLock = SettingsRepository.current.useLockScreen

        if (useLock && isDeviceSecureCompat(this)) {
            authenticate(
                this,
                onSuccess = {
                    hasAuthenticated = true
                    showAuthGuard = false
                    initData()
                },
                onFailed = {
                    finish()
                }
            )
        } else {
            hasAuthenticated = true
            showAuthGuard = false
            initData()
        }
        pendingNavigationRequests.tryEmit(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNavigationRequests.tryEmit(intent)
    }

    private fun isDeviceSecureCompat(context: Context): Boolean {
        val km = context.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return km.isDeviceSecure
    }

    private fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    // 指纹被识别但不匹配（单次）
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 取消、锁定、连续失败后触发
                    onFailed()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auth_request))
            .setSubtitle(getString(R.string.unlock_method))
            .setDescription(getString(R.string.unlock_desc))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onStart() {
        super.onStart()
        registerPipReceiver()
    }

    private fun registerPipReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_PLAY)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, RECEIVER_NOT_EXPORTED)
            LogUtil.i("pipmode", "✅ registerReceiver with RECEIVER_NOT_EXPORTED")
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActionReceiver, filter)
            LogUtil.i("pipmode", "✅ registerReceiver (legacy)")
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(pipActionReceiver)
    }

    override fun onSupportNavigateUp(): Boolean {
        return if (mainBackStack.removeLast()) {
            true
        } else {
            super.onSupportNavigateUp()
        }
    }

    /**
     * 抽屉头部「切换站点」：hanime 里番 → javchu(AV) → nJAV → 回到 hanime，三站循环。
     *
     * 旧实现只在 hanime 与 javchu 之间二选一（[currentSite] 属于 [ANIME_URL] 就跳到
     * javchu，否则跳回来），所以 nJAV 永远切不进去；而且它只改 domainName、不写
     * siteSource，即使切过去网络层仍按 hanime 分流。这里把第三态补齐并同步数据源。
     */
    private fun confirmSiteSwitch() {
        showSiteSwitchConfirm = false
        val currentSite = SettingsRepository.baseUrl
        val avSite = HANIME_URL[3]                 // javchu.com：hanime 线上的 AV 站，仍归 hanime 数据源
        val njavSite = HanimeConstants.NJAV_URL    // nJAV：独立数据源
        val onNjav = SettingsRepository.isNjavSite
        // 回到 hanime 里番时用哪个镜像：优先用户之前记下的那个，但必须真的是
        // [ANIME_URL] 成员（selectedBaseUrl 有可能被写成 javchu，否则就回不到「里番」了）。
        val comebackSite = SettingsRepository.selectedBaseUrl
            .takeIf { it in ANIME_URL }
            ?: ANIME_URL[0]

        lifecycleScope.launch {
            SettingsRepository.update {
                when {
                    // nJAV → 回到 hanime 里番
                    onNjav -> it.copy(
                        domainName = comebackSite,
                        selectedBaseUrl = comebackSite,
                        siteSource = SiteSource.Hanime1,
                    )

                    // 已经在 javchu 上 → nJAV
                    currentSite == avSite -> it.copy(
                        domainName = njavSite,
                        selectedBaseUrl = comebackSite,
                        siteSource = SiteSource.Njav,
                        // 自定义镜像只指向某一个站点，跟不过去，切换站点时关掉。
                        useCustomMirrorSite = false,
                    )

                    // hanime 里番（含自定义镜像）→ javchu
                    else -> it.copy(
                        domainName = avSite,
                        selectedBaseUrl = currentSite.takeIf { site -> site in ANIME_URL } ?: comebackSite,
                        siteSource = SiteSource.Hanime1,
                        useCustomMirrorSite = false,
                    )
                }
            }
            delay(500)
            ActivityManager.restart(killProcess = true)
        }
    }

    fun openLogin() {
        mainBackStack.add(LoginRoute, launchSingleTop = true)
    }

    fun showLogoutConfirmDialog(closeCurrentPageOnConfirm: Boolean = false) {
        logoutDialogCloseCurrentPage = closeCurrentPageOnConfirm
    }

    private fun confirmLogout() {
        val closeCurrentPage = logoutDialogCloseCurrentPage ?: return
        logoutDialogCloseCurrentPage = null
        if (closeCurrentPage) {
            mainBackStack.removeLast()
        }
        logoutWithRefresh()
    }

    fun logoutWithRefresh() {
        lifecycleScope.launch {
            logout()
            viewModel.getHomePage()
        }
    }

    fun showVideoDetailFragment(videoCode: String, fileUri: String? = null) {
        mainBackStack.add(VideoRoute(videoCode, fileUri))
    }

    fun registerCurrentVideoHost(host: VideoPageHost?) {
        currentVideoHost = host
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val currentFragment = currentVideoHost

        val allowPip = SettingsRepository.current.allowPipMode

        LogUtil.i("pipmode", "enter pip mode?\n$currentFragment\nallowpip:$allowPip\n")

        if (currentFragment?.shouldEnterPip() == true && allowPip) {
            LogUtil.i("pipmode", "enter pip mode")
            currentFragment.enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        val currentFragment = currentVideoHost

        currentFragment?.onPipModeChanged(isInPictureInPictureMode)
    }

    fun togglePlayPause() {
        currentVideoHost?.togglePlayPause()
    }

    init {
        if (!(BuildConfig.DEBUG && isX86_64Device)) {
            System.loadLibrary("chino")
        }
    }
}
