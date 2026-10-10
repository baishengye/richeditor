package com.itc.richeditorv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EditorSnapshot] 的 JSON 解析测试。
 *
 * 这些用例同时也是 JS 侧字段名的"契约测试"：
 * 键名一旦在 `web/src/snapshot.ts` 里被改动而这里没同步，测试就会失败。
 */
class EditorSnapshotTest {

    @Test
    fun `解析完整快照`() {
        val json = """
            {"align":"center","bold":true,"italic":false,"underline":true,"strike":false,"code":false,
             "heading":2,"bulletList":false,"orderedList":true,"blockquote":false,
             "codeBlock":false,"link":"https://example.com","canUndo":true,"canRedo":false,
             "editable":true,"empty":false}
        """.trimIndent()

        val state = EditorSnapshot.fromJson(json)

        assertEquals(TextAlign.Center, state.align)
        assertTrue(state.bold)
        assertFalse(state.italic)
        assertTrue(state.underline)
        assertFalse(state.strike)
        assertFalse(state.code)
        assertEquals(2, state.heading)
        assertFalse(state.bulletList)
        assertTrue(state.orderedList)
        assertTrue(state.inList)
        assertFalse(state.blockquote)
        assertFalse(state.codeBlock)
        assertEquals("https://example.com", state.link)
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)
        assertTrue(state.editable)
        assertFalse(state.empty)
    }

    @Test
    fun `link 为 null 时解析为 null`() {
        val state = EditorSnapshot.fromJson("""{"link":null}""")
        assertNull(state.link)
    }

    @Test
    fun `link 为空串时归一化为 null`() {
        val state = EditorSnapshot.fromJson("""{"link":""}""")
        assertNull(state.link)
    }

    @Test
    fun `字段缺失时回退到默认值`() {
        val state = EditorSnapshot.fromJson("{}")
        assertEquals(EditorSnapshot.EMPTY, state)
    }

    @Test
    fun `非法 JSON 不抛异常而是回退默认值`() {
        // 快照只驱动 UI，解析失败也不该让编辑器功能中断
        val state = EditorSnapshot.fromJson("这不是 JSON")
        assertEquals(EditorSnapshot.EMPTY, state)
    }

    @Test
    fun `空对象串也不抛异常`() {
        assertEquals(EditorSnapshot.EMPTY, EditorSnapshot.fromJson(""))
    }

    @Test
    fun `默认值语义正确`() {
        val state = EditorSnapshot.EMPTY
        assertTrue("默认应可编辑", state.editable)
        assertTrue("默认应为空文档", state.empty)
        assertFalse(state.canUndo)
        assertFalse(state.inList)
        assertEquals("默认应为左对齐", TextAlign.Left, state.align)
    }

    @Test
    fun `align 缺失或未知时归一化为左对齐`() {
        assertEquals(TextAlign.Left, EditorSnapshot.fromJson("{}").align)
        assertEquals(TextAlign.Left, EditorSnapshot.fromJson("""{"align":""}""").align)
        assertEquals(TextAlign.Left, EditorSnapshot.fromJson("""{"align":"justify"}""").align)
    }

    @Test
    fun `align 三种取值均可解析`() {
        assertEquals(TextAlign.Left, EditorSnapshot.fromJson("""{"align":"left"}""").align)
        assertEquals(TextAlign.Center, EditorSnapshot.fromJson("""{"align":"center"}""").align)
        assertEquals(TextAlign.Right, EditorSnapshot.fromJson("""{"align":"right"}""").align)
    }

    @Test
    fun `image 字段解析正确`() {
        assertFalse(EditorSnapshot.fromJson("{}").image)
        assertTrue(EditorSnapshot.fromJson("""{"image":true}""").image)
        assertFalse(EditorSnapshot.fromJson("""{"image":false}""").image)
    }

    @Test
    fun `未知字段被忽略`() {
        val state = EditorSnapshot.fromJson("""{"bold":true,"未来新增字段":123}""")
        assertTrue(state.bold)
    }
}
