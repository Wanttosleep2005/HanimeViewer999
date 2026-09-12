package io.github.daisukikaffuchino.han1meviewer.util

import io.github.daisukikaffuchino.utils.LogUtil
import android.widget.ImageView
import coil.ImageLoader
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

@Suppress("NOTHING_TO_INLINE")
object HImageMeower {

    private const val TAG = "CoilImageNyanner"

    /**
     * 封面图用的 client。
     *
     * ⚠️ 和 [io.github.daisukikaffuchino.han1meviewer.logic.network.ServiceCreator.downloadClient]
     * 一样，这里也**必须**挂代理：漏挂的表现是「文字内容能加载、封面图一张都出不来」，
     * 用户完全看不出是代理没生效。
     */
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .proxySelector(HProxySelector())
        .proxyAuthenticator(HProxyAuthenticator.http)
        .dns(HDns())
        .build()

    private val imageLoader = ImageLoader.Builder(applicationContext)
        .okHttpClient(okHttpClient)
        .build()

    suspend fun execute(data: Any): ImageResult {
        LogUtil.d(TAG, "execute: $data")
        return imageLoader.execute(
            ImageRequest.Builder(applicationContext).data(data).build()
        )
    }

    inline fun placeholder(height: Int, width: Int, blur: Int = 8) =
        "https://picsum.photos/$width/$height/?blur=$blur"

    fun ImageView.loadUnhappily(data: Any?, fallbackData: Any?) {
        LogUtil.d(TAG, "primary: $data, fallback: $fallbackData")
        val primaryRequest = ImageRequest.Builder(context)
            .data(data ?: fallbackData)
            .crossfade(true)
            .target(this)
            .listener(object : ImageRequest.Listener {
                private val ivRef = WeakReference(this@loadUnhappily)
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    fallbackData?.let { ivRef.get()?.loadUnhappily(it, null) }
                }
            }).build()
        context.imageLoader.enqueue(primaryRequest)
    }
}
