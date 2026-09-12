package io.github.daisukikaffuchino.han1meviewer.logic.njav

import io.github.daisukikaffuchino.han1meviewer.logic.exception.ParseException

/**
 * nJAV 详情页把真正的播放地址藏在 Dean Edwards 风格的 packer 里，形如：
 *
 * ```
 * eval(function(p,a,c,k,e,d){…}('e=\'8://7.6/5-4-3-2-1/d.0\';…',15,15,'m3u8|33e2b3d9a030|…'.split('|'),0,{}))
 * ```
 *
 * 这个 packer 是**确定性**的：把 `p` 里「按 base-a 编码的字典下标」token 依次用
 * 词边界 `\b` 换回字典 `k` 里的词即可还原。`\b` 很关键 —— 它保证已经插进去的词
 * 不会被二次替换（例如 `source842` 里的 `8` 不该被当成下标 8 的 token）。
 * 还原之后就能直接正则出 surrit 的 m3u8 地址。
 *
 * ⚠️ 解码函数 `e(c)` 的递归段在 `c < a` 时是空串，**但数字位永远要拼上**：
 * 写成 `if (c < a) "" else …` 会让下标 0 变成空 token，正则就会把整页文字换烂。
 */
object NjavPacker {

    private const val PACKER_MARK = "eval(function(p,a,c,k,e,"

    /**
     * 匹配 packer 的调用尾巴：`}('p',a,c,'k'.split('|'),0,{}))`
     *
     * ⚠️⚠️ **开头的 `}` 必须写成 `\}`。**
     *
     * 裸 `}` 在桌面 JVM 的 `java.util.regex` 里是合法字面量，但 **Android 的
     * `java.util.regex` 是 ICU4C 后端**，ICU 把它判为**语法错误**
     * （`U_REGEX_RULE_SYNTAX`）。也就是说这条正则**只在手机上炸**：
     * PC 上跑离线脚本、单元测试全都正常（实测：Java `Pattern.compile` 通过，
     * ICU `uregex_open` 报错），极其容易漏。
     *
     * 后果还远不止「匹配不到」——它写在 `object` 的字段初始化里，也就是 `<clinit>`：
     * 一旦抛 `PatternSyntaxException`，**这个类会被永久标记为「初始化失败」**，
     * 之后**每次**引用都变成 `NoClassDefFoundError: <类的全限定名>`，
     * 报错里只有一个类名、看不出任何原因。mod.6.5 之前用户看到的
     * 「加载失败，请重试 / io.github.…logic.njav.NjavPacker」就是它 ——
     * 这条线索把排查带偏了整整一轮（网络、反爬、DNS 全查过一遍）。
     * 而且它只打详情页：列表页不经过 [NjavPacker]，所以「列表正常、点进去必挂」。
     *
     * 现在改为 [compileRegex] + `by lazy`：正则写错也只是每次调用抛一条带说明的
     * 异常，不会再把整个类搞成永久不可用。
     */
    private val PACKED by lazy {
        compileRegex(
            """\}\(\s*'((?:\\.|[^'\\])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'\\])*)'\s*\.split\('\|'\)"""
        )
    }

    private val M3U8 by lazy {
        compileRegex("""https?://[^\s'"\\<>]+\.m3u8(?:\?[^\s'"\\<>#]*)?""")
    }

    /**
     * 编译正则，失败时给出**能看懂**的异常。
     *
     * 不把 `Regex(…)` 直接写在字段上的两个理由：
     * 1. `<clinit>` 里抛异常会让整个类永久不可用，且报错只剩类名（见 [PACKED] 的说明）；
     * 2. 用 `lazy` 之后，正则即使写错，也只是每次调用抛一条带原因的
     *    [ParseException]，类本身仍可用，问题一眼可见。
     */
    private fun compileRegex(pattern: String): Regex =
        runCatching { Regex(pattern) }.getOrElse { cause ->
            throw ParseException(
                "nJAV 解包正则编译失败（Android 的 ICU 正则引擎比桌面 JVM 严格）：${cause.message}"
            )
        }

    /** 还原 body 里所有 packer 块并拼接；没有 packer 时返回空串。 */
    fun unpackAll(body: String): String {
        if (!body.contains(PACKER_MARK)) return ""
        val sb = StringBuilder()
        for (m in PACKED.findAll(body)) {
            val base = m.groupValues[2].toIntOrNull() ?: continue
            val count = m.groupValues[3].toIntOrNull() ?: continue
            sb.append(unpack(m.groupValues[1], base, count, m.groupValues[4])).append('\n')
        }
        return sb.toString()
    }

    /** 提取页面里出现的全部 m3u8 地址（去重、保持出现顺序）。 */
    fun extractM3u8(body: String): List<String> {
        val unpacked = unpackAll(body)
        val text = if (unpacked.isEmpty()) body else unpacked + '\n' + body
        return M3U8.findAll(text).map { it.value }.distinct().toList()
    }

    private fun unpack(packed: String, base: Int, count: Int, dictionary: String): String {
        if (base <= 1 || count <= 0) return ""
        var text = unescape(packed)
        val words = unescape(dictionary).split('|')
        for (i in (count - 1) downTo 0) {
            val word = words.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: continue
            val token = Regex("\\b" + Regex.escape(encode(i, base)) + "\\b")
            text = token.replace(text, Regex.escapeReplacement(word))
        }
        return text
    }

    /**
     * 等价于 packer 里的 `e(c)`：
     * `(c < a ? "" : e(parseInt(c / a))) + (c % a > 35 ? fromCharCode(c % a + 29) : (c % a).toString(36))`
     */
    private fun encode(c: Int, base: Int): String =
        (if (c < base) "" else encode(c / base, base)) + digit(c % base)

    private fun digit(c: Int): String =
        if (c > 35) (c + 29).toChar().toString() else c.toString(36)

    /** 还原 JS 字符串字面量里的转义（`\'` `\\` `\n` `\xNN` `\uNNNN` 等）。 */
    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\' || i == s.length - 1) {
                sb.append(ch)
                i++
                continue
            }
            when (val next = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'x' -> {
                    val v = s.substring(i + 2, minOf(i + 4, s.length)).toIntOrNull(16)
                    if (v == null) { sb.append('x'); i += 2 } else { sb.append(v.toChar()); i += 4 }
                }
                'u' -> {
                    val v = s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)
                    if (v == null) { sb.append('u'); i += 2 } else { sb.append(v.toChar()); i += 6 }
                }
                else -> { sb.append(next); i += 2 }
            }
        }
        return sb.toString()
    }
}
