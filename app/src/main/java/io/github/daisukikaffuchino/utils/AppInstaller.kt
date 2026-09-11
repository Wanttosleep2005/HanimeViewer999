package io.github.daisukikaffuchino.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.daisukikaffuchino.han1meviewer.FILE_PROVIDER_AUTHORITY
import java.io.File

/**
 * 拉起系统安装器安装下载好的更新包。
 *
 * @return `true` 已成功拉起安装器；`false` 表示缺「安装未知应用」权限 ——
 *   此时已经把用户送到该应用对应的授权页，**APK 仍在 cacheDir 里不会重下**，
 *   授权后回到 App 再点一次「安装」即可。
 */
fun Context.installUpdateApk(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false

    // targetSdk 29+ 必须走这个开关。没有授权就直接 startActivity 安装包，
    // 系统会静默拦掉（什么都不发生），很容易被误判成「点了没反应」。
    if (!packageManager.canRequestPackageInstalls()) {
        return runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData("package:$packageName".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    val apkUri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching { startActivity(intent) }.isSuccess
}

/** 是否已获「安装未知应用」授权。UI 用它决定按钮文案是「安装」还是「去授权」。 */
fun Context.canInstallApk(): Boolean = packageManager.canRequestPackageInstalls()
