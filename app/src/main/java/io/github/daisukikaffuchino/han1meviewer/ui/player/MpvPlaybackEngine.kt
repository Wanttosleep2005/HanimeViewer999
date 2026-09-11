package io.github.daisukikaffuchino.han1meviewer.ui.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.core.net.toUri
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.USER_AGENT
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.util.AnimeShaders
import io.github.daisukikaffuchino.han1meviewer.util.AnimeShaders.getCert
import io.github.daisukikaffuchino.utils.LogUtil
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MpvPlaybackEngine(
    private val context: Context,
) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackEngineState())
    private var currentSurface: Surface? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var detachedFd: Int? = null
    private var pendingRequest: PlaybackRequest? = null
    private var initialized = false
    private var released = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0
    private var hasRenderedFrame = false
    private var hasReachedEndOfFile = false
    private var lastKnownPositionMs = 0L
    private var lastKnownDurationMs = 0L
    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = publishState()
        override fun eventProperty(property: String, value: Double) = publishState()
        override fun eventProperty(property: String, value: Long) = publishState()
        override fun eventProperty(property: String, value: Boolean) {
            if (property == "eof-reached") hasReachedEndOfFile = value
            publishState()
        }
        override fun eventProperty(property: String, value: String) = publishState()

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.mpvEventId.MPV_EVENT_START_FILE -> {
                    hasReachedEndOfFile = false
                    lastKnownPositionMs = 0L
                    lastKnownDurationMs = 0L
                    mutableState.value = mutableState.value.copy(
                        phase = PlaybackPhase.Preparing,
                        isBuffering = true,
                        errorMessage = null,
                    )
                }

                MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED -> {
                    pendingRequest?.let { request ->
                        MPVLib.setPropertyDouble("speed", requestSpeed.toDouble())
                        if (request.startPositionMs > 0L) {
                            seekTo(request.startPositionMs)
                        }
                        if (request.playWhenReady) startPlayback()
                    }
                    mutableState.value = mutableState.value.copy(
                        phase = PlaybackPhase.Ready,
                        isBuffering = false,
                    )
                }

                MPVLib.mpvEventId.MPV_EVENT_END_FILE -> {
                    val playbackState = mutableState.value
                    val reachedRecordedDuration = lastKnownDurationMs > 0L &&
                            lastKnownPositionMs >=
                            (lastKnownDurationMs - NORMAL_END_TOLERANCE_MS).coerceAtLeast(0L)
                    val endedNormally = hasReachedEndOfFile ||
                            MPVLib.getPropertyBoolean("eof-reached") == true ||
                            reachedRecordedDuration
                    mutableState.value = playbackState.copy(
                        phase = if (endedNormally) PlaybackPhase.Ended else PlaybackPhase.Error,
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = if (endedNormally) null else "Playback failed before reaching end of file",
                    )
                }

                MPVLib.mpvEventId.MPV_EVENT_SHUTDOWN -> {
                    mutableState.value = PlaybackEngineState()
                }
            }
        }
    }
    private var requestSpeed = PlayerDefaults.DEFAULT_SPEED

    override val state: StateFlow<PlaybackEngineState> = mutableState.asStateFlow()

    override fun load(request: PlaybackRequest) {
        check(!released) { "Playback engine has already been released" }
        initializeIfNeeded()
        pendingRequest = request
        lastVideoWidth = 0
        lastVideoHeight = 0
        hasRenderedFrame = false
        MPVLib.setPropertyBoolean("pause", true)
        MPVLib.command(arrayOf("loadfile", "", "replace"))
        val path = prepareUri(request.uri.toUri())
        if (path == null) {
            mutableState.value = mutableState.value.copy(
                phase = PlaybackPhase.Error,
                errorMessage = "Unable to open media URI",
            )
            return
        }
        applyHttpHeaders(request.headers)
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.command(arrayOf("loadfile", path, "replace"))
        currentSurface?.let {
            MPVLib.attachSurface(it)
            applySurfaceSize()
        }
        mutableState.value = mutableState.value.copy(
            phase = PlaybackPhase.Preparing,
            isBuffering = true,
            errorMessage = null,
            videoWidth = 0,
            videoHeight = 0,
            hasRenderedFirstFrame = false,
        )
    }

    override fun play() = startPlayback()

    override fun pause() {
        MPVLib.setPropertyBoolean("pause", true)
        publishState()
    }

    override fun seekTo(positionMs: Long) {
        MPVLib.command(arrayOf("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute", "exact"))
        publishState()
    }

    override fun setPlaybackSpeed(speed: Float) {
        requestSpeed = speed.coerceIn(0.25f, 5f)
        MPVLib.setPropertyDouble("speed", requestSpeed.toDouble())
        publishState()
    }

    override fun setVolume(volume: Float) {
        MPVLib.setPropertyDouble("volume", (volume.coerceIn(0f, 1f) * 100f).toDouble())
    }

    override fun attachSurface(surface: Surface) {
        if (released) return
        currentSurface = surface
        if (initialized) {
            MPVLib.attachSurface(surface)
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", videoOutput)
            applySurfaceSize()
        }
    }

    override fun detachSurface(surface: Surface) {
        if (released) return
        if (currentSurface == surface) {
            currentSurface = null
            if (initialized) {
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setOptionString("force-window", "no")
                MPVLib.detachSurface()
            }
        }
    }

    fun updateSurfaceSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        surfaceWidth = width
        surfaceHeight = height
        applySurfaceSize()
    }

    fun setSuperResolution(index: Int) {
        val shader = AnimeShaders.getShader(context, index)
        MPVLib.command(arrayOf("change-list", "glsl-shaders", "set", shader))
    }

    override fun release() {
        if (released) return
        released = true
        if (initialized) {
            MPVLib.setPropertyBoolean("pause", true)
            MPVLib.command(arrayOf("loadfile", "", "replace"))
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
            currentSurface = null
            MPVLib.removeObserver(observer)
        }
        closeCurrentFile()
        scope.cancel()
        mutableState.value = PlaybackEngineState()
    }

    private fun initializeIfNeeded() {
        if (initialized) return
        mpvOptions().forEach { (key, value) -> MPVLib.setOptionString(key, value) }
        parseCustomMpvParams().forEach { (key, value) -> MPVLib.setOptionString(key, value) }
        MPVLib.observeProperty("time-pos", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("video-params/w", MPVLib.mpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("video-params/h", MPVLib.mpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("demuxer-cache-duration", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.addObserver(observer)
        initialized = true
        scope.launch {
            while (isActive) {
                publishState()
                delay(250L.milliseconds)
            }
        }
    }

    private fun startPlayback() {
        MPVLib.setPropertyBoolean("pause", false)
        publishState()
    }

    /**
     * 把 [PlaybackRequest.headers] 透传给 mpv。
     *
     * ⚠️ mpv 走的是**自己的**网络栈，`PlaybackRequest.headers` 不会自动生效 ——
     * ExoPlayer（`setDefaultRequestProperties`）和 MediaPlayer（`setDataSource(…, headers)`）
     * 都能拿到，唯独 mpv 必须显式写进 `http-header-fields`，否则：
     *
     * - nJAV 的视频在 surrit.com 上，**不带 Referer 直接 403**（Cloudflare）；
     * - 表现就是列表 / 封面都正常，一点进详情页就「加载失败」。
     *
     * 必须在 `loadfile` **之前**设置（网络流是在 load 时才打开的）。每次 load 都重设一遍，
     * 这样 hanime 的视频不会继承上一次 nJAV 留下的 Referer。
     *
     * ---
     *
     * ## 为什么是 `setPropertyString` 而不是 `setOptionString`
     *
     * 这里踩过一个很隐蔽的坑，也是「修了却没生效」的原因：
     *
     * `MPVLib.init()`（即 `mpv_initialize()`）在 [io.github.daisukikaffuchino.han1meviewer.HanimeApplication]
     * 的 `onCreate()` 里**应用一启动就调用了**，而 `mpv_set_option*()` 按 mpv 的规定
     * 「只能在 `mpv_initialize()` 之前使用」。运行期再调 `setOptionString` 会被**静默忽略**
     * —— 不抛异常、不打日志（`MPVLib.setOptionString` 的 JNI 层丢弃了返回码），
     * 于是看起来「代码明明写对了，Referer 就是没发出去」。
     *
     * 正确的运行期入口是 `mpv_set_property*()`：client.h 明确写着自 mpv 0.21 起
     * *"this can be used to set options in general"*。`MPVLib.setPropertyString()` 对应
     * 它，能在 init 之后真正改到 `http-header-fields`。
     *
     * 值格式是 mpv 规定的 `Name: value,Name2: value2` 逗号分隔串；空串表示不发额外头。
     */
    private fun applyHttpHeaders(headers: Map<String, String>) {
        val value = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString(",") { (name, headerValue) -> "$name: $headerValue" }
        MPVLib.setPropertyString("http-header-fields", value)
        if (value.isNotEmpty()) {
            LogUtil.d(TAG, "mpv http-header-fields = $value")
        }
    }

    private fun applySurfaceSize() {
        if (!initialized || released || currentSurface == null) return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        MPVLib.setPropertyString("android-surface-size", "${surfaceWidth}x${surfaceHeight}")
        if (MPVLib.getPropertyBoolean("pause") == true) {
            MPVLib.command(arrayOf("seek", "0", "relative", "exact"))
        }
    }

    private fun publishState() {
        if (!initialized || released) return
        MPVLib.getPropertyDouble("time-pos")?.let {
            lastKnownPositionMs = (it * 1000).toLong().coerceAtLeast(0L)
        }
        MPVLib.getPropertyDouble("duration")?.let {
            lastKnownDurationMs = (it * 1000).toLong().coerceAtLeast(0L)
        }
        val buffered = MPVLib.getPropertyDouble("demuxer-cache-duration") ?: 0.0
        val paused = MPVLib.getPropertyBoolean("pause") ?: true
        val width = MPVLib.getPropertyInt("video-params/w") ?: 0
        val height = MPVLib.getPropertyInt("video-params/h") ?: 0
        if (width > 0 && height > 0) {
            lastVideoWidth = width
            lastVideoHeight = height
            hasRenderedFrame = true
        }
        mutableState.value = mutableState.value.copy(
            isPlaying = !paused,
            isBuffering = !paused && lastKnownDurationMs > 0L && lastKnownPositionMs == 0L,
            positionMs = lastKnownPositionMs,
            durationMs = lastKnownDurationMs,
            bufferedPositionMs = (lastKnownPositionMs + buffered * 1000).toLong().coerceAtLeast(0L),
            playbackSpeed = requestSpeed,
            videoWidth = lastVideoWidth,
            videoHeight = lastVideoHeight,
            hasRenderedFirstFrame = hasRenderedFrame,
        )
    }

    private fun prepareUri(uri: Uri): String? {
        return when (uri.scheme) {
            "http", "https" -> uri.toString()
            "file", "content" -> {
                closeCurrentFile()
                currentPfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                detachedFd = currentPfd?.detachFd()
                detachedFd?.let { "fd://$it" }
            }
            else -> null
        }
    }

    private fun closeCurrentFile() {
        currentPfd?.close()
        detachedFd?.let { runCatching { ParcelFileDescriptor.adoptFd(it).close() } }
        currentPfd = null
        detachedFd = null
    }

    private fun mpvOptions(): Map<String, String> = buildMap {
        put("vo", videoOutput)
        put("profile", SettingsRepository.mpvProfile.takeIf { it == "gpu-hq" || it == "fast" } ?: "default")
        put("hwdec", when (SettingsRepository.mpvHwdec) {
            "HW" -> "mediacodec-copy"
            "HW+" -> "mediacodec"
            "Vulkan" -> "vulkan-copy"
            "vulkan+" -> "vulkan"
            "SW" -> "no"
            else -> "auto"
        })
        put("msg-level", "all=" + if (BuildConfig.DEBUG) "debug" else "warn")
        put("cache", "yes")
        put("cache-secs", SettingsRepository.mpvCacheSecs.toString())
        put("vd-lavc-threads", Runtime.getRuntime().availableProcessors().toString())
        put("framedrop", if (SettingsRepository.mpvFramedrop) "vo" else "no")
        put("deband", if (SettingsRepository.mpvDeband) "yes" else "no")
        put("cache-pause", "no")
        put("network-timeout", SettingsRepository.mpvNetworkTimeout.toString())
        put("tls-ca-file", getCert(context))
        put("tls-verify", if (SettingsRepository.mpvTlsVerify) "no" else "yes")
        put("user-agent", USER_AGENT)
        SettingsRepository.proxyIp.takeIf { it.isNotBlank() && SettingsRepository.proxyPort != -1 }?.let { ip ->
            if (SettingsRepository.proxyType == HProxySelector.TYPE_HTTP) {
                put("http-proxy", "http://$ip:${SettingsRepository.proxyPort}")
            }
        }
        if (SettingsRepository.mpvInterpolation) {
            put("interpolation", "yes")
            put("tscale", "oversample")
            put("video-sync", "display-resample")
        }
    }

    private fun parseCustomMpvParams(): Map<String, String> = buildMap {
        SettingsRepository.customMpvParams.split(';').forEach { entry ->
            val parts = entry.trim().split(',', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                put(parts[0].trim(), parts[1].trim())
            }
        }
    }

    private val videoOutput: String
        get() = if (SettingsRepository.enableGPUNextRenderer) "gpu-next" else "gpu"

    private companion object {
        const val TAG = "MpvPlaybackEngine"
        const val NORMAL_END_TOLERANCE_MS = 3_000L
    }
}
