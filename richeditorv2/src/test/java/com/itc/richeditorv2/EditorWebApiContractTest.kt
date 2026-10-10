package com.itc.richeditorv2

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 校验 Kotlin 侧调用的 JS API 方法名，在 JS 侧（`web/src/main.ts` 的 `RichEditorApi`）真实存在。
 *
 * ## 为什么需要这个测试
 * Kotlin → JS 的调用是**字符串拼接**后交给 `evaluateJavascript`：
 *
 * ```kotlin
 * evaluateRaw("window.RichEditor.insertText(${quote(text)})") { ... }
 * ```
 *
 * 这条链路上**没有任何编译期检查**。如果 JS 侧忘了实现 `insertText`，
 * 或者名字写成了 `insertTextAtCursor`，结果是：
 *
 *  - 不报编译错误；
 *  - 运行时不抛异常（JS 只是求值为 `undefined`，`evaluateJavascript` 正常回调 `"null"`）；
 *  - 宿主看到的是"命令返回 true 但什么都没发生"。
 *
 * 这个库已经因为同一类问题踩过坑（`setContent` 在某些情况下静默无效），
 * 所以这里用源码扫描把问题提前到构建阶段。
 *
 * ## 为什么可以读源码
 * `web/` 目录与 `src/test/` 在同一个仓库里，Gradle 单测的工作目录是模块根目录，
 * 所以能直接读到 `web/src/main.ts`。若文件不存在（例如只 checkout 了 Android 部分），
 * 测试会 skip 而不是失败。
 */
class EditorWebApiContractTest {

    private val viewSource: String by lazy {
        File("src/main/java/com/itc/richeditorv2/RichEditorView.kt").readText()
    }

    private val webSource: String? by lazy {
        val file = File("web/src/main.ts")
        if (file.exists()) file.readText() else null
    }

    /**
     * 抽出 Kotlin 里所有对 JS API 的调用。
     *
     * 两种写法都要覆盖，否则这个测试会漏掉大部分调用：
     *
     *  - `evaluateRaw("${api()}.getJSON()")` —— 显式拼上 `window.RichEditor.`
     *  - `runScript("setContent(...)")` —— [RichEditorView.runScript] 内部会补上 `window.RichEditor.`
     */
    private fun kotlinCalledApiMethods(): Set<String> {
        // 名字必须以字母开头：`evaluateRaw("${api()}.$body")` 里的 `$body`
        // 是 [RichEditorView.runScript] 自己的形参占位，不是方法名。
        val direct = Regex("""api\(\)\}\.([A-Za-z][A-Za-z0-9_$]*)""")
        val viaRunScript = Regex("""runScript\(\s*"([A-Za-z][A-Za-z0-9_$]*)""")
        return buildSet {
            direct.findAll(viewSource).forEach { add(it.groupValues[1]) }
            viaRunScript.findAll(viewSource).forEach { add(it.groupValues[1]) }
        }
    }

    /** 抽出 `web/src/main.ts` 里 `export interface RichEditorApi { ... }` 的方法名 */
    private fun jsApiMethods(source: String): Set<String> {
        val start = source.indexOf("export interface RichEditorApi")
        assertTrue("web/src/main.ts 里找不到 RichEditorApi 接口，契约测试已失效", start >= 0)
        val open = source.indexOf('{', start)
        val close = source.indexOf("\n}", open)
        assertTrue("RichEditorApi 接口的结束大括号定位失败", close > open)
        val body = source.substring(open + 1, close)
        // 必须开 MULTILINE：否则 `^` 只匹配整个字符串的开头，一行都抓不到
        return Regex("""^\s*([A-Za-z][A-Za-z0-9_$]*)\s*\(""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `Kotlin 调用的每个 JS API 都真实存在`() {
        val web = webSource
        assumeTrue("web/src/main.ts 不存在（只 checkout 了 Android 部分），跳过契约校验", web != null)

        val called = kotlinCalledApiMethods()
        assertTrue("没有从 RichEditorView.kt 里解析出任何 JS API 调用，正则已失效", called.isNotEmpty())

        val available = jsApiMethods(web!!)
        val missing = called - available
        assertTrue(
            "RichEditorView.kt 调用了 JS 侧不存在的 API：$missing。" +
                "JS 方法漏实现或改名都不会有编译错误，只会静默失效，" +
                "请在 web/src/main.ts 的 RichEditorApi 里补上。",
            missing.isEmpty(),
        )
    }

    @Test
    fun `新增的 insertText 在两侧都存在`() {
        val web = webSource
        assumeTrue("web/src/main.ts 不存在，跳过", web != null)

        assertTrue(
            "RichEditorView.insertText 应调用 JS 的 insertText",
            "insertText" in kotlinCalledApiMethods(),
        )
        assertTrue(
            "web/src/main.ts 的 RichEditorApi 应声明 insertText",
            "insertText" in jsApiMethods(web!!),
        )
    }
}
