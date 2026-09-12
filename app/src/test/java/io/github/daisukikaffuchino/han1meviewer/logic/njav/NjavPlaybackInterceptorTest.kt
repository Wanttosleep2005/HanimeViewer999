package io.github.daisukikaffuchino.han1meviewer.logic.njav

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NjavPlaybackInterceptorTest {
    @Test
    fun addsHeadersToCdnSubdomains() {
        val request = intercept("https://video.surrit.com/example/video0.jpeg")
        assertEquals(NjavNetwork.REFERER, request.header("Referer"))
        assertEquals(NjavNetwork.ORIGIN, request.header("Origin"))
    }

    @Test
    fun addsHeadersForEveryDownloadRequestKind() {
        listOf("playlist.m3u8", "720p/video.m3u8", "init.mp4", "video0.jpeg", "video.mp4").forEach { path ->
            val request = intercept("https://fourhoi.com/example/$path")
            assertEquals(NjavNetwork.REFERER, request.header("Referer"))
            assertEquals(NjavNetwork.ORIGIN, request.header("Origin"))
        }
    }

    @Test
    fun preservesExplicitHeadersAndRange() {
        val original = Request.Builder().url("https://surrit.com/video0.jpeg")
            .header("Referer", "https://njavtv.com/example")
            .header("Origin", NjavNetwork.ORIGIN)
            .header("Range", "bytes=0-0").build()
        assertEquals(original.headers, intercept(original).headers)
    }

    @Test
    fun doesNotMatchCdnNamesOutsideHost() {
        listOf(
            "https://surrit.com.example.org/playlist.m3u8",
            "https://example.org/surrit.com/playlist.m3u8",
            "https://example.org/playlist.m3u8?from=fourhoi.com",
            "https://notsurrit.com/playlist.m3u8",
        ).forEach { url ->
            assertTrue(url, NjavNetwork.playbackHeadersFor(url).isEmpty())
            assertTrue(url, intercept(url).headers.size == 0)
        }
    }

    private fun intercept(url: String): Request = intercept(Request.Builder().url(url).build())

    private fun intercept(request: Request): Request {
        val chain = Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java),
        ) { _, method, args ->
            when (method.name) {
                "request" -> request
                "proceed" -> Response.Builder()
                    .request(args!![0] as Request)
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ByteArray(0).toResponseBody()).build()
                else -> error("Unexpected chain method: ${method.name}")
            }
        } as Interceptor.Chain
        return NjavPlaybackInterceptor().intercept(chain).use { it.request }
    }
}
