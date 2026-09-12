package io.github.daisukikaffuchino.han1meviewer.logic.njav

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * nJAV 女优索引解析的回归测试。
 *
 * 夹具全部是 **2026-09 从线上抓下来的真实 HTML**（只做了删减，没有改写结构）——
 * 站点是 SSR + 随机 `dm###` 前缀，靠肉眼看网页很容易写出「本地能跑、上线就空」的选择器。
 */
class NjavParserActressTest {

    /**
     * 索引页里的一个正常卡片。
     *
     * ⚠️ 注意链接里的名字是**繁体**（`結` = `%E7%B5%90`），而 `<h4>` 显示的是**简体**
     * （`结` = `%E7%BB%93`）—— 这是本功能最容易踩的坑：必须照抄 `href`，不能拿显示名现编码。
     */
    private val cardHotate = """
        <li>
            <div class="space-y-4">
                <a href="https://njavtv.com/dm288/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3" class="text-nord13">
                    <div class="overflow-hidden mx-auto h-20 w-20 rounded-full lg:w-24 lg:h-24">
                        <img src="https://fourhoi.com/actress/26225-t.jpg" alt="波多野结衣" class="object-cover object-top w-full h-full">
                    </div>
                </a>
                <div class="space-y-2">
                    <a href="https://njavtv.com/dm288/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3" class="text-nord13">
                        <h4 class="text-nord13 truncate">波多野结衣</h4>
                        <p class="text-nord10">5668 条影片</p>
                        <p class="text-nord10">2008 出道</p>
                    </a>
                </div>
            </div>
        </li>
    """.trimIndent()

    /** 同一个索引页里的另一个卡片：前缀是 `dm8128`（说明前缀确实是逐条不同的随机值）。 */
    private val cardOtsuki = """
        <li>
            <div class="space-y-4">
                <a href="https://njavtv.com/dm8128/cn/actresses/%E5%A4%A7%E6%A7%BB%E3%81%B2%E3%81%B3%E3%81%8D" class="text-nord13">
                    <div class="overflow-hidden mx-auto h-20 w-20 rounded-full lg:w-24 lg:h-24">
                        <img src="https://fourhoi.com/actress/30130-t.jpg" alt="大槻响" class="object-cover object-top w-full h-full">
                    </div>
                </a>
                <div class="space-y-2">
                    <a href="https://njavtv.com/dm8128/cn/actresses/%E5%A4%A7%E6%A7%BB%E3%81%B2%E3%81%B3%E3%81%8D" class="text-nord13">
                        <h4 class="text-nord13 truncate">大槻响</h4>
                        <p class="text-nord10">3597 条影片</p>
                        <p class="text-nord10">2009 出道</p>
                    </a>
                </div>
            </div>
        </li>
    """.trimIndent()

    /** 页头的下拉菜单：也是 `<li><a href=…>`，但没有 `<h4>`，必须被忽略。 */
    private val navItem = """
        <li><a href="https://njavtv.com/cn/new" class="block px-4 py-2">影片</a></li>
    """.trimIndent()

    /** `/actresses/ranking` 是「排行榜」而不是某个人，也必须被忽略。 */
    private val rankingItem = """
        <li><a href="https://njavtv.com/cn/actresses/ranking"><h4>排行榜</h4></a></li>
    """.trimIndent()

    private val indexPage = """
        <html><body>
        <nav><ul>$navItem$rankingItem</ul></nav>
        <ul class="mx-auto grid grid-cols-2 gap-4">$cardHotate$cardOtsuki</ul>
        <a href="https://njavtv.com/cn/actresses?page=2" rel="next">下一页</a>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesActressCardsAndIgnoresNavigation() {
        val actresses = NjavParser.actressList(indexPage)

        assertEquals(listOf("波多野结衣", "大槻响"), actresses.map { it.name })
        assertEquals("https://fourhoi.com/actress/26225-t.jpg", actresses[0].avatarUrl)
        assertEquals(5668, actresses[0].videoCount)
        assertEquals(2008, actresses[0].debutYear)
        assertEquals(3597, actresses[1].videoCount)
        assertEquals(2009, actresses[1].debutYear)
    }

    /**
     * 路径必须**原样来自 `href`**（含繁体的百分号编码），并且丢掉会变的 `dm###` 前缀。
     *
     * 如果实现改成「拿显示名现编码」，这里拿到的会是 `%E7%BB%93`（简体结）而不是
     * `%E7%B5%90`（繁体結），测试就会红 —— 这正是它要守住的点。
     */
    @Test
    fun keepsHrefEncodingAndDropsVolatilePrefix() {
        val actresses = NjavParser.actressList(indexPage)

        assertEquals(
            "actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3",
            actresses[0].path,
        )
        // 不含任何 dm### 前缀、不含 /cn/ 语言段，也不含 BASE_URL。
        assertTrue(actresses[0].path.startsWith("actresses/"))
        assertTrue(actresses.none { it.path.contains("dm288") || it.path.contains("dm8128") })
    }

    @Test
    fun resolvesActressUrlsThroughListUrl() {
        val path = NjavParser.actressList(indexPage)[0].path

        assertEquals(
            "https://njavtv.com/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3",
            NjavNetwork.actressUrl(path, page = 1),
        )
        assertEquals(
            "https://njavtv.com/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3?page=3",
            NjavNetwork.actressUrl(path, page = 3),
        )
        assertEquals("https://njavtv.com/cn/actresses", NjavNetwork.actressIndexUrl(1))
        assertEquals("https://njavtv.com/cn/actresses?page=7", NjavNetwork.actressIndexUrl(7))
    }

    @Test
    fun flagsNextPageOnActressIndex() {
        assertTrue(NjavParser.hasNextPage(indexPage))
        assertTrue(NjavParser.actressList("<html><body></body></html>").isEmpty())
    }

    /**
     * 拼好的地址经过 OkHttp 的 `HttpUrl`（也就是 Retrofit `@Url` 走的那条路）之后**必须原样**。
     *
     * 这条测试守的是「双重编码」这个坑：如果实现里先把名字 encode 一遍、再让 Retrofit 对
     * 已经带 `%` 的字符串 encode 第二遍，会变成 `%25E6%25B3%25A2…`，站点直接 404 ——
     * 而且这种错**本地看不出来**，只有在手机上才会变成「一片空白」。
     */
    @Test
    fun encodedPathSurvivesHttpUrlParsing() {
        val path = NjavParser.actressList(indexPage)[0].path
        val url = NjavNetwork.actressUrl(path, page = 1)

        val parsed = url.toHttpUrlOrNull()
        assertEquals(url, parsed?.toString())
        assertEquals(
            "/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3",
            parsed?.encodedPath,
        )
    }

    /**
     * 女优详情页（`/cn/actresses/<name>`）的影片卡片和分类页**共用同一套结构**，
     * 所以 [NjavParser.videoList] 可以原样复用 —— 这条测试就是在钉住这个前提。
     *
     * 夹具取自 `https://njavtv.com/cn/actresses/波多野结衣` 的第一张卡。
     */
    @Test
    fun reusesVideoListParserOnActressDetailPage() {
        val actressPage = """
            <html><body>
            <h1>波多野結衣出演的 AV 在线看</h1>
            <div class="grid xl:grid-cols-4 gap-5">
                <div>
                    <div
                        @mouseenter="setPreview('f9572f5d')"
                        @mouseleave="setPreview()"
                        @click="clickPreview('f9572f5d')"
                        class="thumbnail group"
                    >
                        <div class="relative aspect-w-16 aspect-h-9 rounded overflow-hidden shadow-lg">
                            <a href="https://njavtv.com/cn/mird-281" alt="mird-281">
                                <video class="preview hidden" data-src="https://fourhoi.com/mird-281/preview.mp4"></video>
                                <img class="lozad w-full" data-src="https://fourhoi.com/mird-281/cover-t.jpg" alt="MOODYZ粉丝答谢祭 完整版">
                            </a>
                            <a href="https://njavtv.com/cn/mird-281" alt="mird-281">
                                <span class="absolute bottom-1 right-1 rounded-lg px-2 py-1 text-xs">1:31:39</span>
                            </a>
                        </div>
                        <div class="my-2 text-sm text-nord4 truncate">
                            <a class="text-secondary" href="https://njavtv.com/cn/mird-281" alt="mird-281">
                                MIRD-281 MOODYZ粉丝答谢祭 完整版
                            </a>
                        </div>
                    </div>
                </div>
            </div>
            <a href="https://njavtv.com/dm288/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3?page=2" rel="next">下一页</a>
            </body></html>
        """.trimIndent()

        val videos = NjavParser.videoList(actressPage)

        assertEquals(1, videos.size)
        assertEquals("mird-281", videos[0].videoCode)
        assertEquals("MIRD-281 MOODYZ粉丝答谢祭 完整版", videos[0].title)
        assertEquals("https://fourhoi.com/mird-281/cover-t.jpg", videos[0].coverUrl)
        assertEquals("1:31:39", videos[0].duration)
        assertTrue(NjavParser.hasNextPage(actressPage))
    }

    @Test
    fun ignoresLinksThatAreNotActressPages() {
        assertNull(NjavParser.actressList("<ul>$navItem$rankingItem</ul>").firstOrNull())
    }
}
