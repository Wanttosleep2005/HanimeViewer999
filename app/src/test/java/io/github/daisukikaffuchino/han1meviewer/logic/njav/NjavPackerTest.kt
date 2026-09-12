package io.github.daisukikaffuchino.han1meviewer.logic.njav

import org.junit.Assert.assertEquals
import org.junit.Test

class NjavPackerTest {
    @Test
    fun preservesSignedPlaylistUrls() {
        val url = "https://surrit.com/example/playlist.m3u8?token=TEST_SIGNATURE&expires=123"
        assertEquals(listOf(url), NjavPacker.extractM3u8("source='$url';"))
    }

    @Test
    fun preservesSignedPlaylistUrlsAfterUnpacking() {
        val packed = """eval(function(p,a,c,k,e,d){}('0=\'1://2/3.4?5=6&7=8\';',""" +
            "9,9,'source|https|surrit.com|playlist|m3u8|token|TEST_SIGNATURE|expires|123'.split('|'),0,{}))"
        assertEquals(
            listOf("https://surrit.com/playlist.m3u8?token=TEST_SIGNATURE&expires=123"),
            NjavPacker.extractM3u8(packed),
        )
    }

    @Test
    fun keepsUnsignedUrlsAndDeduplicates() {
        val url = "https://surrit.com/example/720p/video.m3u8"
        assertEquals(listOf(url), NjavPacker.extractM3u8("source='$url'; other=\"$url\";"))
    }
}
