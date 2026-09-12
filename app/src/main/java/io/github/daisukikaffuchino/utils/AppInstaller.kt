package io.github.daisukikaffuchino.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.daisukikaffuchino.han1meviewer.FILE_PROVIDER_AUTHORITY
import java.io.File

/**
 * 拉起系统安装器的结果。
 *
 * 为什么不再用 `Boolean`：以前只有「成功 / 没权限」两种结果，于是**包本身是坏的**
 * 这一情况被并进了「没权限」里 —— 界面提示「请先允许本应用安装未知来源应用」，
 * 用户跑去授权、回来再点，还是装不上，真正的原因（藏在系统安装器那句
 * 「解析安装包错误」背后）从头到尾没露过面。见 [installUpdateApk]。
 */
sealed interface InstallResult {
    /** 已成功拉起系统安装器。 */
    data object Started : InstallResult

    /** 缺「安装未知应用」权限，已把用户送到授权页；APK 还在，授权后回来再点一次即可。 */
    data object PermissionRequired : InstallResult

    /**
     * 这个包根本没法装（文件不存在 / 系统解析不了 / 不是本应用）。
     * 坏包**已经被删掉**，调用方应当把下载状态一并复位，让用户重新下载。
     */
    data class BrokenPackage(val reason: String) : InstallResult
}

/**
 * 拉起系统安装器安装下载好的更新包。
 *
 * ⚠️ **交出去之前必须自己先解析一遍。**
 *
 * 系统安装器对「装不上的包」只会甩一句「解析安装包错误」，不给原因、也不给下一步。
 * 而自用构建的更新包出这种问题，最常见的成因不是包本身，而是**下载时被续传逻辑拼坏了**
 * —— `update.apk` 是固定文件名、跨版本复用的落点，若盘里留着上一版的字节，
 * 续传会把新版本的尾巴接到旧版本的头上，拼出一个「长度恰好正确、文件头也是 PK」
 * 的四不像（实测：长度校验通过，zip 解析器报 `Bad magic number for central directory`）。
 *
 * 这里用**与安装器同源**的系统解析器先判一次：判得过才交出去；判不过就把原因说清楚，
 * 顺手把坏包清掉，用户再点一次「立即更新」即可。见 `AppUpdateDownloader` 的相应说明。
 *
 * @return [InstallResult.Started] 已拉起安装器；[InstallResult.PermissionRequired] 已跳到授权页；
 *   [InstallResult.BrokenPackage] 包不可用（已被清理，需要重新下载）。
 */
fun Context.installUpdateApk(file: File): InstallResult {
    if (!file.isFile || file.length() <= 0L) {
        return InstallResult.BrokenPackage("更新包不存在或为空")
    }

    @Suppress("DEPRECATION")
    val info = runCatching { packageManager.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull()
    if (info == null) {
        runCatching { file.delete() }
        return InstallResult.BrokenPackage("系统无法解析该文件（下载被中断或被拼接坏了）")
    }
    if (info.packageName != packageName) {
        runCatching { file.delete() }
        return InstallResult.BrokenPackage("包名不符（包内是 ${info.packageName}）")
    }

    // targetSdk 29+ 必须走这个开关。没有授权就直接 startActivity 安装包，
    // 系统会静默拦掉（什么都不发生），很容易被误判成「点了没反应」。
    if (!packageManager.canRequestPackageInstalls()) {
        val opened = runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData("package:$packageName".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        return if (opened) {
            InstallResult.PermissionRequired
        } else {
            InstallResult.BrokenPackage("无法打开「安装未知应用」授权页，请到系统设置里手动授予")
        }
    }

    val apkUri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching { startActivity(intent) }.fold(
        onSuccess = { InstallResult.Started },
        onFailure = { InstallResult.BrokenPackage("无法拉起系统安装器：${it.message}") },
    )
}

/** 是否已获「安装未知应用」授权。UI 用它决定按钮文案是「安装」还是「去授权」。 */
fun Context.canInstallApk(): Boolean = packageManager.canRequestPackageInstalls()
