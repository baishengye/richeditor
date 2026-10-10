package com.itc.richeditorv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EditorCommand] 的契约测试。
 *
 * 命令名是 JS 侧按字符串匹配的，写错不会编译报错、只会在运行时静默失败
 * （表现为"点了没反应"）。这里用测试把这类错误拦在构建阶段。
 *
 * 注意：JS 侧真正的白名单在 `web/src/protocol.ts` 的 `SIMPLE_COMMANDS` 与
 * `commands.ts` 的别名里，两边必须同步修改。
 */
class EditorCommandTest {

    @Test
    fun `jsName 唯一`() {
        val duplicates = EditorCommand.entries
            .groupBy { it.jsName }
            .filterValues { it.size > 1 }
            .keys

        assertTrue("存在重复的 jsName: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `jsName 非空且为合法标识符`() {
        val pattern = Regex("^[a-zA-Z][a-zA-Z0-9]*$")

        EditorCommand.entries.forEach { command ->
            assertTrue(
                "${command.name} 的 jsName '${command.jsName}' 不是合法 JS 标识符",
                pattern.matches(command.jsName),
            )
        }
    }

    @Test
    fun `jsName 全部以小写动词或 set 开头`() {
        // tiptap 的命名约定：toggleXxx / setXxx / unsetXxx / undo / redo / selectAll / clearContent
        // 对齐用 alignXxx（JS 侧是对 setTextAlign 的无参别名），删除用 deleteSelection
        val allowedPrefixes = listOf(
            "toggle", "set", "unset", "undo", "redo", "select", "clear", "align", "delete",
        )

        EditorCommand.entries.forEach { command ->
            assertTrue(
                "${command.name} 的 jsName '${command.jsName}' 不符合 tiptap 命名约定",
                allowedPrefixes.any { command.jsName.startsWith(it) },
            )
        }
    }

    @Test
    fun `标题命令映射到对应级别`() {
        assertEquals("toggleHeading1", EditorCommand.Heading1.jsName)
        assertEquals("toggleHeading2", EditorCommand.Heading2.jsName)
        assertEquals("toggleHeading3", EditorCommand.Heading3.jsName)
    }

    @Test
    fun `标记类命令均为可勾选`() {
        val toggles = listOf(
            EditorCommand.Bold, EditorCommand.Italic, EditorCommand.Underline,
            EditorCommand.Strike, EditorCommand.Code,
            EditorCommand.Heading1, EditorCommand.Heading2, EditorCommand.Heading3,
            EditorCommand.Paragraph, EditorCommand.BulletList, EditorCommand.OrderedList,
            EditorCommand.Blockquote, EditorCommand.CodeBlock,
            EditorCommand.AlignLeft, EditorCommand.AlignCenter, EditorCommand.AlignRight,
        )

        toggles.forEach { assertTrue("$it 应为可勾选", it.isToggle) }
    }
}
