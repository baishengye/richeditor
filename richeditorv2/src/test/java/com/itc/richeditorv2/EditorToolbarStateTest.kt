package com.itc.richeditorv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EditorToolbarState] 的映射测试。
 *
 * 重点是「按钮选中态由状态决定」这一语义，以及标题组互斥、动作型命令无选中态。
 */
class EditorToolbarStateTest {

    private val bold = EditorSnapshot.EMPTY.copy(bold = true)

    @Test
    fun `标记类命令直接映射对应字段`() {
        assertTrue(EditorToolbarState.isSelected(bold, EditorCommand.Bold))
        assertFalse(EditorToolbarState.isSelected(bold, EditorCommand.Italic))

        val all = EditorSnapshot.EMPTY.copy(italic = true, underline = true, strike = true, code = true)
        assertTrue(EditorToolbarState.isSelected(all, EditorCommand.Italic))
        assertTrue(EditorToolbarState.isSelected(all, EditorCommand.Underline))
        assertTrue(EditorToolbarState.isSelected(all, EditorCommand.Strike))
        assertTrue(EditorToolbarState.isSelected(all, EditorCommand.Code))
    }

    @Test
    fun `标题组互斥且同时只有一个按钮选中`() {
        val h2 = EditorSnapshot.EMPTY.copy(heading = 2)

        assertTrue(EditorToolbarState.isSelected(h2, EditorCommand.Heading2))
        assertFalse(EditorToolbarState.isSelected(h2, EditorCommand.Heading1))
        assertFalse(EditorToolbarState.isSelected(h2, EditorCommand.Heading3))
        assertFalse(EditorToolbarState.isSelected(h2, EditorCommand.Paragraph))
    }

    @Test
    fun `heading 为 0 时正文按钮选中`() {
        val paragraph = EditorSnapshot.EMPTY.copy(heading = 0)

        assertTrue(EditorToolbarState.isSelected(paragraph, EditorCommand.Paragraph))
        assertFalse(EditorToolbarState.isSelected(paragraph, EditorCommand.Heading1))
        assertFalse(EditorToolbarState.isSelected(paragraph, EditorCommand.Heading2))
        assertFalse(EditorToolbarState.isSelected(paragraph, EditorCommand.Heading3))
    }

    @Test
    fun `列表与引用映射正确`() {
        val state = EditorSnapshot.EMPTY.copy(bulletList = true, blockquote = true, codeBlock = true)

        assertTrue(EditorToolbarState.isSelected(state, EditorCommand.BulletList))
        assertFalse(EditorToolbarState.isSelected(state, EditorCommand.OrderedList))
        assertTrue(EditorToolbarState.isSelected(state, EditorCommand.Blockquote))
        assertTrue(EditorToolbarState.isSelected(state, EditorCommand.CodeBlock))
    }

    @Test
    fun `对齐三按钮同组互斥`() {
        val left = EditorSnapshot.EMPTY.copy(align = TextAlign.Left)
        assertTrue(EditorToolbarState.isSelected(left, EditorCommand.AlignLeft))
        assertFalse(EditorToolbarState.isSelected(left, EditorCommand.AlignCenter))
        assertFalse(EditorToolbarState.isSelected(left, EditorCommand.AlignRight))

        val center = EditorSnapshot.EMPTY.copy(align = TextAlign.Center)
        assertFalse(EditorToolbarState.isSelected(center, EditorCommand.AlignLeft))
        assertTrue(EditorToolbarState.isSelected(center, EditorCommand.AlignCenter))
        assertFalse(EditorToolbarState.isSelected(center, EditorCommand.AlignRight))

        val right = EditorSnapshot.EMPTY.copy(align = TextAlign.Right)
        assertFalse(EditorToolbarState.isSelected(right, EditorCommand.AlignLeft))
        assertFalse(EditorToolbarState.isSelected(right, EditorCommand.AlignCenter))
        assertTrue(EditorToolbarState.isSelected(right, EditorCommand.AlignRight))
    }

    @Test
    fun `默认状态下左对齐按钮选中`() {
        // 单选组必须总有一项选中，否则工具栏会看起来像全灭
        assertTrue(EditorToolbarState.isSelected(EditorSnapshot.EMPTY, EditorCommand.AlignLeft))
    }

    @Test
    fun `动作型命令恒无选中态`() {
        val everythingOn = EditorSnapshot(
            bold = true, italic = true, underline = true, strike = true, code = true,
            heading = 1, bulletList = true, orderedList = true, blockquote = true, codeBlock = true,
            link = "https://x", canUndo = true, canRedo = true, editable = true, empty = false,
        )

        val actionCommands = listOf(
            EditorCommand.Undo, EditorCommand.Redo, EditorCommand.SelectAll,
            EditorCommand.ClearContent, EditorCommand.HorizontalRule,
            EditorCommand.HardBreak, EditorCommand.UnsetLink, EditorCommand.DeleteSelection,
        )
        actionCommands.forEach { command ->
            assertFalse(
                "$command 属于动作型命令，不应有选中态",
                EditorToolbarState.isSelected(everythingOn, command),
            )
            assertFalse("$command 不应被声明为可勾选", command.isToggle)
        }
    }

    @Test
    fun `撤销重做的可用性跟随历史栈`() {
        val fresh = EditorSnapshot.EMPTY
        assertFalse(EditorToolbarState.isEnabled(fresh, EditorCommand.Undo))
        assertFalse(EditorToolbarState.isEnabled(fresh, EditorCommand.Redo))

        val afterEdit = EditorSnapshot.EMPTY.copy(canUndo = true)
        assertTrue(EditorToolbarState.isEnabled(afterEdit, EditorCommand.Undo))
        assertFalse(EditorToolbarState.isEnabled(afterEdit, EditorCommand.Redo))

        val afterUndo = EditorSnapshot.EMPTY.copy(canUndo = false, canRedo = true)
        assertFalse(EditorToolbarState.isEnabled(afterUndo, EditorCommand.Undo))
        assertTrue(EditorToolbarState.isEnabled(afterUndo, EditorCommand.Redo))
    }

    @Test
    fun `只读模式下所有命令不可用`() {
        val readOnly = EditorSnapshot.EMPTY.copy(editable = false, canUndo = true, canRedo = true)

        EditorCommand.values().forEach { command ->
            assertFalse("$command 在只读模式下应不可用", EditorToolbarState.isEnabled(readOnly, command))
        }
    }

    @Test
    fun `可编辑模式下撤销重做仍受历史栈约束`() {
        val editable = EditorSnapshot.EMPTY.copy(editable = true, canUndo = true)

        assertTrue(EditorToolbarState.isEnabled(editable, EditorCommand.Undo))
        assertFalse(EditorToolbarState.isEnabled(editable, EditorCommand.Redo))
        assertTrue(EditorToolbarState.isEnabled(editable, EditorCommand.Bold))
    }
}
