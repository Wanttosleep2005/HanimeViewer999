package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.getHanimeShareText
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo
import io.github.daisukikaffuchino.han1meviewer.logic.entity.CheckInType
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AnnouncementDialog
import io.github.daisukikaffuchino.han1meviewer.ui.component.ConfirmDialog
import io.github.daisukikaffuchino.han1meviewer.ui.component.TripleButtonDialog
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.HomePageScreen
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.HomePageViewModel
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.HomeUiEvent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.LocalSearchHistoryQuery
import io.github.daisukikaffuchino.utils.InstallResult
import io.github.daisukikaffuchino.utils.installUpdateApk
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.rememberCopyTextToClipboard
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.CheckInCalendarViewModel
import io.github.daisukikaffuchino.utils.SonnerToast
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun HomeRouteScreen(
    activity: MainActivity,
    isDrawerOpen: Boolean,
    showNavigationIcon: Boolean,
    onOpenDrawer: () -> Unit,
    onNavigateToPreview: () -> Unit,
    onNavigateToSearch: (String?) -> Unit,
    onNavigateToSearchAdvanced: (Map<String, String>) -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val viewModel = activity.viewModel
    val checkInEnabled by SettingsRepository.checkInEnabledFlow.collectAsStateWithLifecycle()
    val checkInViewModel: CheckInCalendarViewModel? = if (checkInEnabled) composeViewModel() else null
    val copyTextToClipboard = rememberCopyTextToClipboard()
    val confirmToExit = stringResource(R.string.confirm_to_exit)
    val confirmExitMessage = stringResource(R.string.confirm_exit_message)
    val cancel = stringResource(R.string.cancel)
    val exit = stringResource(R.string.exit)
    var showExitDialog by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf<Announcement?>(null) }
    val updateDownloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()

    /**
     * 拉起安装器，并按三种结果分别收尾。
     *
     * 关键在第三种：包不可用（多半是下载时被续传逻辑拼坏了，见 `AppUpdateDownloader`）时
     * 光弹一句错误是不够的 —— 坏包已被清掉，若不把下载状态一并复位，卡片会一直停在
     * 「立即安装」，用户点多少次都只会再看到同一句错误。复位后卡片回到「立即更新」，
     * 点一下就是一次干净的重下。
     */
    fun installOrReport(apkFile: java.io.File) {
        when (val install = activity.installUpdateApk(apkFile)) {
            InstallResult.Started -> Unit

            // 缺「安装未知应用」权限 —— 已经跳去授权页，授权后回来再点「立即安装」
            InstallResult.PermissionRequired ->
                SonnerToast.error(R.string.update_install_permission_required)

            is InstallResult.BrokenPackage -> {
                LogUtil.e("HomeRoute", "更新包不可用，已复位下载状态：${install.reason}")
                viewModel.clearUpdateDownloadState()
                SonnerToast.error(R.string.update_package_broken)
            }
        }
    }

    /**
     * 包下好之后自动拉起一次安装器。
     *
     * 用 `LaunchedEffect` 而不是直接在事件回调里装：下载是异步的，完成时机不确定；
     * 而且用户可能中途切走又切回来。这里以「已就绪的 apk 路径」为 key，
     * 同一个包只会自动拉起一次，之后要重装得手动点按钮。
     */
    var autoInstallTriggeredFor by remember { mutableStateOf<String?>(null) }
    val readyApk = (updateDownloadState as? HomePageViewModel.UpdateDownloadState.ReadyToInstall)
        ?.apkFile
    LaunchedEffect(readyApk?.absolutePath) {
        val path = readyApk?.absolutePath ?: return@LaunchedEffect
        if (autoInstallTriggeredFor == path) return@LaunchedEffect
        autoInstallTriggeredFor = path
        installOrReport(readyApk)
    }

    CompositionLocalProvider(
        LocalSearchHistoryQuery provides { keyword: String ->
            DatabaseRepo.SearchHistory.loadAll(keyword).first().map { it.query }
        }
    ) {
        HomePageScreen(
            viewModel = viewModel,
            isDrawerOpen = isDrawerOpen,
            showNavigationIcon = showNavigationIcon,
            onEvent = { event ->
                when (event) {
                    is HomeUiEvent.OpenDrawer -> onOpenDrawer()
                    is HomeUiEvent.NavigateToPreview -> onNavigateToPreview()
                    is HomeUiEvent.OpenSearchPage -> onNavigateToSearch(event.query)
                    is HomeUiEvent.NavigateToSearchAdvanced -> onNavigateToSearchAdvanced(event.params)
                    is HomeUiEvent.OpenVideo -> onNavigateToVideo(event.videoCode)
                    is HomeUiEvent.LongPressVideoCopy -> {
                        copyTextToClipboard(getHanimeShareText(event.videoTitle, event.videoCode))
                        SonnerToast.success(R.string.copy_to_clipboard)
                    }
                    is HomeUiEvent.ShowAnnouncementDialog -> { announcement = event.announcement }
                    is HomeUiEvent.ShowExitDialog -> { showExitDialog = true }
                    is HomeUiEvent.UpdateAction -> {
                        when (val state = viewModel.updateDownloadState.value) {
                            // 已经下好了 → 直接装（授权被拒过的话就是在这里重试）
                            is HomePageViewModel.UpdateDownloadState.ReadyToInstall -> {
                                autoInstallTriggeredFor = state.apkFile.absolutePath
                                installOrReport(state.apkFile)
                            }
                            // 下载中：按钮此时是禁用的，这里兜底
                            is HomePageViewModel.UpdateDownloadState.Downloading,
                            is HomePageViewModel.UpdateDownloadState.Pending -> Unit

                            // 未开始或上次失败 → 走应用内下载。
                            // versionCode 直接用事件里带来的（不要反查 appUpdateState ——
                            // DEBUG 的模拟卡片与真实状态是两回事）
                            else -> viewModel.startUpdateDownload(
                                url = event.downloadUrl,
                                versionCode = event.versionCode,
                            )
                        }
                    }
                    is HomeUiEvent.IgnoreUpdate -> viewModel.ignoreUpdate(event.versionCode)
                }
            }
        )
    }

    if (showExitDialog && checkInEnabled) {
        TripleButtonDialog(
            visible = true,
            title = confirmToExit,
            message = stringResource(R.string.finished_masturbating),
            negativeText = stringResource(R.string.do_more),
            neutralText = stringResource(R.string.checkout_exit),
            positiveText = exit,
            onNegative = { showExitDialog = false },
            onNeutral = {
                checkInViewModel?.addRecord(
                    LocalDate.now(),
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                    CheckInType.MASTURBATION.storeName,
                    "",
                )
                activity.finish()
            },
            onPositive = { activity.finish() },
            onDismiss = { showExitDialog = false },
        )
    } else if (showExitDialog) {
        ConfirmDialog(
            visible = true,
            title = confirmToExit,
            message = confirmExitMessage,
            confirmText = exit,
            dismissText = cancel,
            onConfirm = { activity.finish() },
            onDismiss = { showExitDialog = false },
        )
    }

    announcement?.let { data ->
        AnnouncementDialog(
            announcementData = data,
            onDismiss = { announcement = null },
        )
    }
}
