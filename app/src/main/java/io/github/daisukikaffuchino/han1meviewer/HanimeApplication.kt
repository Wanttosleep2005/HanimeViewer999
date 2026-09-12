package io.github.daisukikaffuchino.han1meviewer

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.datastore.DataStoreManager
import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.ImageNetworkClient
import io.github.daisukikaffuchino.han1meviewer.ui.crash.CrashHandler
import io.github.daisukikaffuchino.han1meviewer.util.AnimeShaders
import io.github.daisukikaffuchino.han1meviewer.util.AppLanguageManager
import io.github.daisukikaffuchino.utils.ActivityManager
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.applicationContext as globalApplicationContext
import `is`.xyz.mpv.MPVLib
import java.lang.ref.WeakReference
import java.net.ProxySelector

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 17:32
 */
class HanimeApplication : Application(), Application.ActivityLifecycleCallbacks,
    SingletonImageLoader.Factory {

    companion object {
        const val TAG = "HanimeApplication"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        globalApplicationContext = this
    }

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
        DataStoreManager.initialize(this)
        SettingsRepository.install(DataStoreManager)
        AppLanguageManager.applyStoredLanguage(this)
        registerActivityLifecycleCallbacks(this)
        ProxySelector.setDefault(HProxySelector())
        HProxySelector.rebuildNetwork()
        initNotificationChannel()
        MPVLib.create(applicationContext)
        MPVLib.init()

        // SOCKS5 的用户名/密码认证只能通过全局 java.net.Authenticator 提供
        // （那段协商发生在 Socket 建连内部，OkHttp 看不到），必须在任何建连之前装好。
        HProxyAuthenticator.installSocksAuthenticator()

        // 【8.1】预热一次中转探活：这样第一个视频请求失败后，能立刻知道「该不该绕中转」，
        // 不必先等一次探活超时。不阻塞启动，失败也无所谓。
        CdnRelay.warmUp()

        if (AnimeShaders.copyShaderAssets(applicationContext) <= 0) {
            LogUtil.w(TAG, "Shader 复制失败")
        }
        if (AnimeShaders.copyCertAssets(applicationContext) <= 0) {
            LogUtil.w(TAG, "cert 复制失败")
        }
        val selected = SettingsRepository.fakeLauncherIcon
        switchLauncher(selected)
    }

    private fun initNotificationChannel() {
        val nm = NotificationManagerCompat.from(this)

        val hanimeDownloadChannel = NotificationChannelCompat.Builder(
            DOWNLOAD_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("Hanime Download").build()
        nm.createNotificationChannel(hanimeDownloadChannel)

        val appUpdateChannel = NotificationChannelCompat.Builder(
            UPDATE_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("App Update").build()
        nm.createNotificationChannel(appUpdateChannel)
    }
    fun switchLauncher(alias: String) {
        val pm = packageManager

        val allAliases = listOf(
            "io.github.daisukikaffuchino.han1meviewer.LauncherAliasDefault",
            "io.github.daisukikaffuchino.han1meviewer.LauncherFakeCalc",
            "io.github.daisukikaffuchino.han1meviewer.LauncherFakeCornhub",
            "io.github.daisukikaffuchino.han1meviewer.LauncherFakeXxt"
        )

        allAliases.forEach { a ->
            val state = if (a == alias)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            pm.setComponentEnabledSetting(
                ComponentName(this, a),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /**
     * Coil 3 的全局 [ImageLoader]。
     *
     * 各处的 `AsyncImage` / `SingletonImageLoader.get(context)` 最终都会走到这里，
     * 所以**只在这一处**换掉 OkHttp 栈，全应用的图片就都带上了
     * [ImageNetworkClient]（代理 + [HDns] + 封面图中转兜底），不必去改几十个调用点。
     *
     * ⚠️ 别忘了 Coil **2** 那份（[io.github.daisukikaffuchino.han1meviewer.util.HImageMeower]）
     * 也要用同一个 client —— 这个工程两个版本并存，只配一处会出现
     * 「首页封面通了、下载列表封面不通」这类难查的问题。
     */
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            // 具名传参，且只传 callFactory：
            // OkHttpNetworkFetcherFactory 有 3 个重载，只差后面的默认参数
            // （cacheStrategy / connectivityChecker / ...），写成尾随 lambda 会让
            // 编译器在三者之间产生 Overload resolution ambiguity。
            // 只用具名 callFactory 时，编译器会挑「最少用默认参数」的那个重载。
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { ImageNetworkClient.client })) }
            .build()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        ActivityManager.currentActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
