package io.github.daisukikaffuchino.han1meviewer.util

import io.github.daisukikaffuchino.utils.LogUtil
import android.widget.ImageView
import coil.ImageLoader
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.han1meviewer.logic.network.ImageNetworkClient
import java.lang.ref.WeakReference

@Suppress("NOTHING_TO_INLINE")
object HImageMeower {

    private const val TAG = "CoilImageNyanner"

    /**
     * 封面图用的 client。
     *
     * ⚠️ 和 [io.github.daisukikaffuchino.han1meviewer.logic.network.ServiceCreator.downloadClient]
     * 一样，这里也**必须**挂代理：漏挂的表现是「文字内容能加载、封面图一张都出不来」，
     * 用户完全看不出是代理没生效。
     *
     * 现在统一改用 [ImageNetworkClient]：它除了代理与 [HDns]，还带了
     * [io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.ImageRelayInterceptor]
     * （封面源 `hembed` / `fourhoi` 被封，直连失败时走中转兜底）。
     *
     * ⚠️ 这份是 Coil **2** 的栈，Coil 3 的那份在
     * [io.github.daisukikaffuchino.han1meviewer.HanimeApplication.newImageLoader]。
     * **两处必须指向同一个 client**，否则会出现「一半封面能出、一半出不来」。
     */
    private val okHttpClient = ImageNetworkClient.client

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
