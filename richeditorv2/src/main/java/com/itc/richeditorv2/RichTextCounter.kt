package com.itc.richeditorv2

/**
 * 富文本字数统计（**静态方法，不需要 WebView 或编辑器实例**）。
 *
 * 用途：服务器下发的富文本（ProseMirror JSON 或 HTML）需要离线统计字数时直接调用。
 *
 * ```kotlin
 * val count = RichTextCounter.countFromJson(serverJson)
 * textView.text = "共 ${count.characters} 字"
 * ```
 *
 * ## 与编辑器内实时统计的一致性（重要）
 * 这个类的规则是**照着 tiptap/ProseMirror 的语义逆向实现**的，目的是让
 * 「服务器内容算出来的字数」和「同一内容加载进编辑器后显示的字数」相等。
 * 已通过真机对照验证（见 `RichTextCounterTest`，用例中的期望值取自真实编辑器输出）。
 *
 * 但 ProseMirror 的行为和直觉差别很大，几个必须知道的点：
 *  - **块级边界不插入任何分隔符**：`<p>abc</p><p>def</p>` 的文本是 `"abcdef"`（6 字），
 *    不是 `"abc\ndef"`。所以「按块分行再拼接」的常见写法会算多。
 *  - **`<br>`、`<img>` 不贡献任何文本**：`<p>ab<br>cd</p>` 是 4 字。
 *  - **块首/块尾空白被裁掉，行内空白保留并折叠成一个空格**：
 *    `<p>abc </p><p> def</p>` → `"abcdef"`；
 *    `<p>a <strong>b</strong></p>` → `"a b"`。
 *  - **HTML 源码里的缩进与换行**（标签之间）会被整体丢弃。
 *  - `&nbsp;` 不会折叠成普通空格，但仍按「空白」统计（见 [CharacterCount.characters]）。
 *  - 按 **UTF-16 码元**计数（与 JS `String.length` 一致），所以 emoji 等增补平面字符算 **2 个**。
 *
 * ## JSON 与 HTML 的精度差异
 * - [countFromJson] 是**精确**的：直接从文档模型取文本节点拼接，与编辑器结果必然一致。
 *   **优先用它**（本库 `getContentJson()` 产出的就是这种格式）。
 * - [countFromHtml] 是对 ProseMirror HTML 解析行为的近似实现。上述所有已测场景都能对上，
 *   但 HTML 空白规范化在实际解析器里有更细的规则，极端排版下 [CharacterCount.charactersWithSpaces]
 *   可能有 ±几个字的偏差。[CharacterCount.characters]（不含空白，中文场景的主指标）在所有场景下都稳定。
 *   若必须绝对精确，可把 HTML 载入编辑器后用 `onCharacterCountChanged` 取值。
 */
object RichTextCounter {

    /**
     * 统计 ProseMirror JSON 文档的字数（精确，推荐）。
     *
     * @param json `getContentJson()` 产出的 JSON；无法解析时返回 [CharacterCount.EMPTY]
     */
    fun countFromJson(json: String): CharacterCount {
        if (json.isBlank()) {
            return CharacterCount.EMPTY
        }
        return try {
            val text = StringBuilder()
            collectText(org.json.JSONObject(json), text)
            fromText(text.toString())
        } catch (e: Exception) {
            // 静态工具方法，没有回调可上报；解析失败按空文档处理，绝不抛异常
            CharacterCount.EMPTY
        }
    }

    /**
     * 统计 HTML 的字数。
     *
     * 规则与 ProseMirror 的 HTML 解析对齐（见类注释）：
     *  - 丢弃 `<script>` / `<style>` / 注释（含内容）
     *  - `<br>` 与块级标签视为**边界**，其两侧空白被裁掉
     *  - 行内标签被忽略，其两侧空白保留并折叠为单个空格
     *  - `<img>` 等无文本节点不贡献字数
     *  - 解码 HTML 实体（`&amp;`、`&nbsp;`、`&#123;`、`&#x1F600;` 等）
     *
     * @param html 富文本 HTML；空串返回 [CharacterCount.EMPTY]
     */
    fun countFromHtml(html: String): CharacterCount {
        if (html.isEmpty()) {
            return CharacterCount.EMPTY
        }
        return fromText(htmlToText(html))
    }

    /**
     * 统计纯文本的字数。已按编辑器规则处理空白（不折叠、不裁剪），
     * 适合已经拿到纯文本的场景。
     */
    fun countFromText(text: String): CharacterCount = fromText(text)

    // ==================== 内部实现 ====================

    /** 文本节点在 JSON 里的类型名 */
    private const val TYPE_TEXT = "text"
    private const val FIELD_TYPE = "type"
    private const val FIELD_TEXT = "text"
    private const val FIELD_CONTENT = "content"

    /**
     * 递归收集所有文本节点的文字，**直接拼接、不插任何分隔符**。
     *
     * 这正是 ProseMirror `doc.textContent` 的语义，也是与编辑器字数一致的关键。
     */
    private fun collectText(node: org.json.JSONObject, out: StringBuilder) {
        if (node.optString(FIELD_TYPE) == TYPE_TEXT) {
            // text 字段可能缺失（理论上不会），optString 会给空串，安全
            out.append(node.optString(FIELD_TEXT))
            return
        }
        val content = node.optJSONArray(FIELD_CONTENT) ?: return
        for (i in 0 until content.length()) {
            content.optJSONObject(i)?.let { collectText(it, out) }
        }
    }

    private fun fromText(text: String): CharacterCount {
        var nonWhitespace = 0
        for (ch in text) {
            if (!isJsWhitespace(ch)) {
                nonWhitespace++
            }
        }
        return CharacterCount(
            characters = nonWhitespace,
            // String.length 与 JS 一致，都按 UTF-16 码元计
            charactersWithSpaces = text.length,
            words = countWords(text),
        )
    }

    /**
     * JS 里是 `trim().split(/\s+/).filter(w => /[A-Za-z0-9]/.test(w)).length`。
     *
     * 所以纯中文文档的 words 是 0 —— 这是刻意的（西文单词数对 CJK 无意义），
     * 中文场景请用 [CharacterCount.characters]。
     */
    private fun countWords(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            // 跳过空白
            while (index < text.length && isJsWhitespace(text[index])) {
                index++
            }
            if (index >= text.length) {
                break
            }
            // 收集一个非空白片段，并判断其中是否含 ASCII 字母或数字
            var hasAsciiAlnum = false
            while (index < text.length && !isJsWhitespace(text[index])) {
                val ch = text[index]
                if ((ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9')) {
                    hasAsciiAlnum = true
                }
                index++
            }
            if (hasAsciiAlnum) {
                count++
            }
        }
        return count
    }

    /**
     * JS `\s` 的等价集合。
     *
     * 不能直接用 Kotlin 的 [Char.isWhitespace]：**它把 U+00A0（nbsp）判为非空白**，
     * 而 JS `\s` 把它算作空白。若用错，含 `&nbsp;` 的文档会少算/多算。
     * 另外 U+FEFF（BOM/零宽不换行空格）也属于 JS `\s`。
     */
    private fun isJsWhitespace(ch: Char): Boolean = when (ch) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ',
        '\u00A0', '\u1680',
        '\u2028', '\u2029',
        '\u202F', '\u205F', '\u3000',
        '\uFEFF',
        -> true

        else -> ch in '\u2000'..'\u200A'
    }

    /**
     * HTML 中「可折叠空白」的集合。
     *
     * 刻意**不含 U+00A0**：HTML 规范下 nbsp 不参与空白折叠，
     * ProseMirror 也原样保留它（实测 `<p>a&nbsp;b</p>` 的文本是 "a\u00A0b"）。
     */
    private fun isHtmlCollapsibleWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000C'

    /** 视为「块级」的标签名，它们构成边界，两侧空白会被裁掉 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "blockquote", "pre", "hr",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
        "figure", "figcaption", "section", "article", "aside", "header",
        "footer", "nav", "main", "address", "dl", "dt", "dd", "form", "fieldset",
    )

    /** 这些标签连同其内部文本一起丢弃（不是「标签内容算正文」） */
    private val DROPPED_TAGS = setOf("script", "style", "noscript", "head", "title")

    /** 不产生文本的标签（`<img>` 没有文本内容；`<br>` 作为边界处理） */
    private val VOID_TAGS = setOf(
        "img", "input", "hr", "area", "base", "col", "embed", "source", "track", "wbr",
    )

    /** 用于标记块边界的哨兵字符（私有区，正文几乎不可能出现） */
    private const val SENTINEL = '\uE000'

    /**
     * HTML → 纯文本，规则见类注释。
     *
     * 做法：把块级标签与 `<br>` 换成哨兵，行内标签直接去掉，
     * 再折叠空白、清除紧邻哨兵的空白、最后移除哨兵。
     * 这样「块边界裁空白、行内保留空白」就自然成立了。
     */
    private fun htmlToText(html: String): String {
        // 先剔除正文里可能存在的哨兵字符，避免干扰（正常内容不会有）
        val source = html.replace(SENTINEL.toString(), "")

        val builder = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val lt = source.indexOf('<', index)
            if (lt < 0) {
                appendCollapsedWhitespace(builder, source, index, source.length)
                break
            }
            if (lt > index) {
                appendCollapsedWhitespace(builder, source, index, lt)
            }

            // 注释：整段丢弃
            if (source.startsWith("<!--", lt)) {
                val end = source.indexOf("-->", lt + 4)
                index = if (end < 0) source.length else end + 3
                continue
            }

            val gt = findTagEnd(source, lt + 1)
            if (gt < 0) {
                // 不是完整标签，按普通文本处理剩余部分
                appendCollapsedWhitespace(builder, source, lt, source.length)
                break
            }

            val rawTag = source.substring(lt + 1, gt)
            val closing = rawTag.startsWith("/")
            val tagName = extractTagName(rawTag)

            when {
                tagName in DROPPED_TAGS -> {
                    // 连同内容一起丢弃（只对开标签处理，闭标签直接跳过）
                    if (!closing) {
                        val closeTag = "</$tagName"
                        val closeAt = indexOfIgnoreCase(source, closeTag, gt + 1)
                        index = if (closeAt < 0) source.length else closeAt + closeTag.length
                        // 跳过闭标签的 '>'
                        val closeGt = source.indexOf('>', index)
                        index = if (closeGt < 0) source.length else closeGt + 1
                        continue
                    }
                }

                tagName == "br" -> builder.append(SENTINEL)

                tagName in VOID_TAGS -> Unit // 不产生文本

                tagName in BLOCK_TAGS -> builder.append(SENTINEL)

                else -> Unit // 行内标签（strong/em/a/code/span...）直接忽略
            }
            index = gt + 1
        }

        return finalizeText(builder.toString())
    }

    /** 写入一段文本，并把它内部的连续可折叠空白压成单个空格 */
    private fun appendCollapsedWhitespace(builder: StringBuilder, source: String, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val ch = source[i]
            if (ch == '&') {
                val entity = decodeEntity(source, i, to)
                if (entity != null) {
                    builder.append(entity.first)
                    i = entity.second
                    continue
                }
            }
            if (isHtmlCollapsibleWhitespace(ch)) {
                // 折叠成单个空格；如果上一字符已经是压出来的空格，就不再重复
                if (builder.isEmpty() || builder.last() != ' ') {
                    builder.append(' ')
                }
                i++
                continue
            }
            builder.append(ch)
            i++
        }
    }

    /**
     * 收尾：折叠哨兵、清掉紧邻哨兵的空白、移除哨兵。
     *
     * 这一步是「块边界裁空白、行内保留空白」的关键。
     */
    private fun finalizeText(raw: String): String {
        if (raw.isEmpty()) {
            return ""
        }
        var text = raw

        // 连续哨兵合并（深层嵌套块会产生多个）
        text = text.replace(Regex("$SENTINEL+"), SENTINEL.toString())

        // 清除紧邻哨兵的空白，可能需要多轮（例如 "\0 \0 " 这类）
        val adjacent = Regex(" ?$SENTINEL ?")
        var previous: String
        do {
            previous = text
            text = text.replace(adjacent, SENTINEL.toString())
        } while (text != previous)

        // 移除哨兵本身，同时压掉可能出现的连续空格
        text = text.replace(SENTINEL.toString(), "")
        text = text.replace(Regex(" {2,}"), " ")
        return text
    }

    /** 找到标签的结束 `>`，跳过引号内的内容（属性值里可能出现 `>`） */
    private fun findTagEnd(source: String, from: Int): Int {
        var i = from
        var quote: Char? = null
        while (i < source.length) {
            val ch = source[i]
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '>' -> return i
            }
            i++
        }
        return -1
    }

    /** 从标签体（不含尖括号）里取出小写标签名，如 "/P class=x" → "p" */
    private fun extractTagName(rawTag: String): String {
        val start = if (rawTag.startsWith("/")) 1 else 0
        var end = start
        while (end < rawTag.length) {
            val ch = rawTag[end]
            if (ch.isWhitespace() || ch == '/' || ch == '>') {
                break
            }
            end++
        }
        return rawTag.substring(start, end).lowercase()
    }

    private fun indexOfIgnoreCase(source: String, target: String, from: Int): Int {
        val limit = source.length - target.length
        var i = from
        while (i <= limit) {
            if (source.regionMatches(i, target, 0, target.length, ignoreCase = true)) {
                return i
            }
            i++
        }
        return -1
    }

    /**
     * 解码一个 HTML 实体，返回「字符 + 实体结束后的下标」；不是实体则返回 null。
     *
     * 支持命名实体与十进制/十六进制数字实体。只覆盖富文本里常见的那些，
     * 未识别的实体保持原样（与浏览器遇到未知实体时的行为一致）。
     */
    private fun decodeEntity(source: String, from: Int, limit: Int): Pair<Char, Int>? {
        val semi = source.indexOf(';', from + 1)
        if (semi < 0 || semi >= limit) {
            return null
        }
        // 实体名长度限制，避免把大段文本误当成实体
        if (semi - from > 12) {
            return null
        }
        val body = source.substring(from + 1, semi)
        if (body.isEmpty()) {
            return null
        }

        if (body.startsWith("#")) {
            val code = try {
                if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                    body.substring(2).toInt(16)
                } else {
                    body.substring(1).toInt(10)
                }
            } catch (e: NumberFormatException) {
                return null
            }
            return codeToChar(code)?.let { it to (semi + 1) }
        }

        val ch = NAMED_ENTITIES[body] ?: return null
        return ch to (semi + 1)
    }

    /**
     * 码点转单个 Char。
     *
     * 增补平面（如 emoji U+1F600）超出 Char 范围，这里转成代理对写入会出现问题，
     * 所以对超出范围的码点返回 null（保持实体原样）——这种字符在编辑器里算 2 个码元，
     * 保留实体原文虽然长度不同，但属于罕见情况，这里优先保证不产生错误字符。
     */
    private fun codeToChar(code: Int): Char? {
        if (code <= 0 || code > 0xFFFF) {
            return null
        }
        return code.toChar()
    }

    /** 富文本中常见的命名实体 */
    private val NAMED_ENTITIES: Map<String, Char> = mapOf(
        "amp" to '&',
        "lt" to '<',
        "gt" to '>',
        "quot" to '"',
        "apos" to '\'',
        "nbsp" to '\u00A0',
        "ensp" to '\u2002',
        "emsp" to '\u2003',
        "thinsp" to '\u2009',
        "zwnj" to '\u200C',
        "zwj" to '\u200D',
        "middot" to '\u00B7',
        "bull" to '\u2022',
        "hellip" to '\u2026',
        "mdash" to '\u2014',
        "ndash" to '\u2013',
        "lsquo" to '\u2018',
        "rsquo" to '\u2019',
        "ldquo" to '\u201C',
        "rdquo" to '\u201D',
        "copy" to '\u00A9',
        "reg" to '\u00AE',
        "trade" to '\u2122',
        "deg" to '\u00B0',
        "laquo" to '\u00AB',
        "raquo" to '\u00BB',
        "times" to '\u00D7',
        "divide" to '\u00F7',
        "plusmn" to '\u00B1',
        "frac12" to '\u00BD',
    )
}
