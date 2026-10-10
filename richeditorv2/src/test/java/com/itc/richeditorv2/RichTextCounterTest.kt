package com.itc.richeditorv2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RichTextCounter] 的等价性测试。
 *
 * ## 这些用例的期望值从哪来
 * **全部取自真实编辑器（tiptap 3.31.4 + StarterKit + TextAlign + Image）的输出**，
 * 不是根据我的实现反推的。做法：在浏览器里把 HTML 载入编辑器，
 * 取 `editor.getJSON()` 与 `doc.textContent` 统计结果，作为下面的期望值写死。
 *
 * 所以这个测试真正校验的是「静态统计 == 编辑器内统计」，
 * 而不是「实现和它自己一致」。任何一侧的规则漂移都会让它失败。
 *
 * 若将来升级 tiptap 或增删扩展，**必须重新采集这些基准值**，否则测试会（正确地）失败。
 */
class RichTextCounterTest {

    // ==================== JSON：精确路径 ====================

    @Test
    fun `两个段落文本直接拼接不插分隔符`() {
        // 实测编辑器：<p>abc</p><p>def</p> 的 textContent 是 "abcdef"（6），不是 "abc\ndef"
        val json = """
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"abc"}]},
              {"type":"paragraph","content":[{"type":"text","text":"def"}]}
            ]}
        """.trimIndent()

        val count = RichTextCounter.countFromJson(json)
        assertEquals(6, count.characters)
        assertEquals(6, count.charactersWithSpaces)
        assertEquals(1, count.words)
    }

    @Test
    fun `hardBreak 不贡献文本`() {
        // <p>ab<br>cd</p> → "abcd"
        val json = """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","text":"ab"},{"type":"hardBreak"},{"type":"text","text":"cd"}
            ]}]}
        """.trimIndent()

        val count = RichTextCounter.countFromJson(json)
        assertEquals(4, count.characters)
        assertEquals(4, count.charactersWithSpaces)
    }

    @Test
    fun `内部连续空格被折叠为一个`() {
        // <p>a b   c</p> → "a b c"
        val json = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"a b c"}]}]}"""
        val count = RichTextCounter.countFromJson(json)
        assertEquals(3, count.characters)
        assertEquals(5, count.charactersWithSpaces)
        assertEquals(3, count.words)
    }

    @Test
    fun `纯中文的 words 为 0 但 characters 正确`() {
        val json = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"中文测试"}]}]}"""
        val count = RichTextCounter.countFromJson(json)
        assertEquals(4, count.characters)
        assertEquals(0, count.words)
    }

    @Test
    fun `中英混排统计`() {
        // "中文 English 混排" → characters=11, withSpaces=13, words=1
        val json =
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"中文 English 混排"}]}]}"""
        val count = RichTextCounter.countFromJson(json)
        assertEquals(11, count.characters)
        assertEquals(13, count.charactersWithSpaces)
        assertEquals(1, count.words)
    }

    @Test
    fun `nbsp 按空白计入 characters 但仍保留在字符数里`() {
        // <p>a&nbsp;b</p> → 文本 "a\u00A0b"：characters=2，withSpaces=3
        val json =
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"a\u00A0b"}]}]}"""
        val count = RichTextCounter.countFromJson(json)
        assertEquals(2, count.characters)
        assertEquals(3, count.charactersWithSpaces)
        // nbsp 分隔出的两段都含 ASCII 字母，所以是 2 个单词
        assertEquals(2, count.words)
    }

    @Test
    fun `mark 不影响字数`() {
        // <strong>粗</strong><em>斜</em>普通 → "粗斜普通" = 4
        val json = """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","marks":[{"type":"bold"}],"text":"粗"},
              {"type":"text","marks":[{"type":"italic"}],"text":"斜"},
              {"type":"text","text":"普通"}
            ]}]}
        """.trimIndent()

        val count = RichTextCounter.countFromJson(json)
        assertEquals(4, count.characters)
        assertEquals(4, count.charactersWithSpaces)
    }

    @Test
    fun `标题与列表嵌套`() {
        // <h2>标题</h2><ul><li>甲</li><li>乙</li></ul> → "标题甲乙"
        val json = """
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"标题"}]},
              {"type":"bulletList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"甲"}]}]},
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"乙"}]}]}
              ]}
            ]}
        """.trimIndent()

        val count = RichTextCounter.countFromJson(json)
        assertEquals(4, count.characters)
        assertEquals(0, count.words)
    }

    @Test
    fun `引用嵌套段落`() {
        // <blockquote><p>引用</p></blockquote><p>正文</p> → "引用正文"
        val json = """
            {"type":"doc","content":[
              {"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"引用"}]}]},
              {"type":"paragraph","content":[{"type":"text","text":"正文"}]}
            ]}
        """.trimIndent()

        val count = RichTextCounter.countFromJson(json)
        assertEquals(4, count.characters)
    }

    @Test
    fun `代码块内的空白照常统计`() {
        // <pre><code>const a = 1;</code></pre> → characters=9, withSpaces=12, words=3
        val json = """
            {"type":"doc","content":[
              {"type":"codeBlock","content":[{"type":"text","text":"const a = 1;"}]}
            ]}
        """.trimIndent()

        val count = RichTextCounter.countFromJson(json)
        assertEquals(9, count.characters)
        assertEquals(12, count.charactersWithSpaces)
        assertEquals(3, count.words)
    }

    @Test
    fun `图片与空段落不贡献字数`() {
        val json = """
            {"type":"doc","content":[
              {"type":"paragraph"},
              {"type":"image","attrs":{"src":"data:image/svg+xml;base64,PHN2Zy8+"}},
              {"type":"paragraph"}
            ]}
        """.trimIndent()

        val count = RichTextCounter.countFromJson(json)
        assertEquals(0, count.characters)
        assertEquals(0, count.charactersWithSpaces)
        assertEquals(0, count.words)
    }

    @Test
    fun `链接文字计入字数`() {
        val json = """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","marks":[{"type":"link","attrs":{"href":"https://x.com"}}],"text":"链接文字"}
            ]}]}
        """.trimIndent()

        val count = RichTextCounter.countFromJson(json)
        assertEquals(4, count.characters)
    }

    @Test
    fun `emoji 按 UTF-16 码元计为 2`() {
        // 与 JS String.length 一致：😀 是代理对，占 2 个码元
        val json =
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"hi \uD83D\uDE00\"}]}]}"
        val count = RichTextCounter.countFromJson(json)
        assertEquals(4, count.characters) // h, i, 两个码元
        assertEquals(5, count.charactersWithSpaces)
        assertEquals(1, count.words)
    }

    @Test
    fun `非法或空 JSON 返回空统计且不抛异常`() {
        assertEquals(CharacterCount.EMPTY, RichTextCounter.countFromJson(""))
        assertEquals(CharacterCount.EMPTY, RichTextCounter.countFromJson("   "))
        assertEquals(CharacterCount.EMPTY, RichTextCounter.countFromJson("不是 JSON"))
        assertEquals(CharacterCount.EMPTY, RichTextCounter.countFromJson("{"))
        // 合法 JSON 但没有 content
        assertEquals(CharacterCount.EMPTY, RichTextCounter.countFromJson("""{"type":"doc"}"""))
    }

    // ==================== HTML：近似路径 ====================

    @Test
    fun `HTML 块级边界不插分隔符`() {
        assertEquals(6, RichTextCounter.countFromHtml("<p>abc</p><p>def</p>").characters)
        assertEquals(6, RichTextCounter.countFromHtml("<p>abc</p><p>def</p>").charactersWithSpaces)
    }

    @Test
    fun `HTML 源码里的缩进换行被丢弃`() {
        // 标签之间的空白属于块间空白，ProseMirror 会整体丢弃
        val count = RichTextCounter.countFromHtml("<p>abc</p>\n  <p>def</p>\n")
        assertEquals(6, count.characters)
        assertEquals(6, count.charactersWithSpaces)
    }

    @Test
    fun `块首块尾空白被裁掉而行内空白保留`() {
        // 块尾/块首空白裁掉 → "abcdef"
        val trimmed = RichTextCounter.countFromHtml("<p>abc </p><p> def</p>")
        assertEquals(6, trimmed.characters)
        assertEquals(6, trimmed.charactersWithSpaces)

        // 行内空白保留并折叠为一个 → "a b"
        val inline = RichTextCounter.countFromHtml("<p>a <strong>b</strong></p>")
        assertEquals(2, inline.characters)
        assertEquals(3, inline.charactersWithSpaces)

        // 行内连续空白折叠为一个
        val inline2 = RichTextCounter.countFromHtml("<p>a  <strong>b</strong></p>")
        assertEquals(2, inline2.characters)
        assertEquals(3, inline2.charactersWithSpaces)
    }

    @Test
    fun `纯空白段落与块间空白不产生字符`() {
        // 纯空白段落被丢弃，只剩 "x"
        assertEquals(1, RichTextCounter.countFromHtml("<p>   </p><p>x</p>").characters)

        // 块间空格裁掉后，两个段落直接相邻 → "ab"，共 2 字（注意不是 1 字：a 和 b 都还在）
        val between = RichTextCounter.countFromHtml("<p>a</p> <p>b</p>")
        assertEquals(2, between.characters)
        assertEquals(2, between.charactersWithSpaces)
    }

    @Test
    fun `行内换行与制表符折叠成空格`() {
        val lf = RichTextCounter.countFromHtml("<p>a\nb</p>")
        assertEquals(2, lf.characters)
        assertEquals(3, lf.charactersWithSpaces)

        val tab = RichTextCounter.countFromHtml("<p>a\tb</p>")
        assertEquals(2, tab.characters)
        assertEquals(3, tab.charactersWithSpaces)
    }

    @Test
    fun `br 视为边界因此两侧文本直接相邻`() {
        val count = RichTextCounter.countFromHtml("<p>ab<br>cd</p>")
        assertEquals(4, count.characters)
        assertEquals(4, count.charactersWithSpaces)
    }

    @Test
    fun `标题与列表`() {
        val count = RichTextCounter.countFromHtml("<h2>标题</h2><ul><li>甲</li><li>乙</li></ul>")
        assertEquals(4, count.characters)
    }

    @Test
    fun `代码块`() {
        val count = RichTextCounter.countFromHtml("<pre><code>const a = 1;</code></pre>")
        assertEquals(9, count.characters)
        assertEquals(12, count.charactersWithSpaces)
        assertEquals(3, count.words)
    }

    @Test
    fun `图片不贡献字数`() {
        val count = RichTextCounter.countFromHtml("""<p><img src="data:image/svg+xml;base64,PHN2Zy8+"></p>""")
        assertEquals(0, count.characters)
    }

    @Test
    fun `实体被解码后参与统计`() {
        // &amp; → &，"A & B" 共 3 个非空白字符
        val amp = RichTextCounter.countFromHtml("<p>A &amp; B</p>")
        assertEquals(3, amp.characters)
        assertEquals(5, amp.charactersWithSpaces)

        // &nbsp; → U+00A0：按空白计，但不参与折叠
        val nbsp = RichTextCounter.countFromHtml("<p>a&nbsp;b</p>")
        assertEquals(2, nbsp.characters)
        assertEquals(3, nbsp.charactersWithSpaces)

        // 数字实体
        assertEquals(1, RichTextCounter.countFromHtml("<p>&#20013;</p>").characters)
        assertEquals(1, RichTextCounter.countFromHtml("<p>&#x4E2D;</p>").characters)
    }

    @Test
    fun `script style 与注释被整体丢弃`() {
        val script = RichTextCounter.countFromHtml("<p>a</p><script>var hidden = 1;</script><p>b</p>")
        assertEquals(2, script.characters)

        val style = RichTextCounter.countFromHtml("<style>.x{color:red}</style><p>a</p>")
        assertEquals(1, style.characters)

        val comment = RichTextCounter.countFromHtml("<p>a</p><!-- 这里是注释 --><p>b</p>")
        assertEquals(2, comment.characters)
    }

    @Test
    fun `属性里含尖括号也能正确解析`() {
        val count = RichTextCounter.countFromHtml("""<p><a href="https://x.com?a=1&gt;2" title="a>b">文字</a></p>""")
        assertEquals(2, count.characters)
    }

    @Test
    fun `未闭合标签按 HTML 规范当字面文本处理且不崩溃`() {
        // 完整的 <p> 被当块边界，内容保留
        assertEquals(3, RichTextCounter.countFromHtml("<p>abc").characters)

        // 无法构成完整标签的 '<' 是字面文本（浏览器同样如此），所以这里是 "abc<" 共 4 字
        assertEquals(4, RichTextCounter.countFromHtml("abc<").characters)

        // 同理 "abc<br" 不被识别为换行标签，整段就是 "abc<br" 共 6 字
        assertEquals(6, RichTextCounter.countFromHtml("abc<br").characters)

        // 未闭合的 <script> 会一直吞到文档末尾（与浏览器的容错解析一致），
        // 所以只剩前面的 "a"
        assertEquals(1, RichTextCounter.countFromHtml("<p>a</p><script>var x=1;").characters)
    }

    @Test
    fun `空 HTML 返回空统计`() {
        assertEquals(CharacterCount.EMPTY, RichTextCounter.countFromHtml(""))
        assertEquals(0, RichTextCounter.countFromHtml("<p></p>").characters)
    }

    // ==================== 纯文本入口 ====================

    @Test
    fun `countFromText 不做折叠也不做裁剪`() {
        // 该入口假定调用方给的就是最终文本，因此原样统计
        val count = RichTextCounter.countFromText("a b   c")
        assertEquals(3, count.characters)
        assertEquals(7, count.charactersWithSpaces)
        assertEquals(3, count.words)
    }
}
