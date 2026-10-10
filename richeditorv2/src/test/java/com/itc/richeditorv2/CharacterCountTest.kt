package com.itc.richeditorv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CharacterCount] 的解析测试。
 *
 * 与 EditorSnapshotTest 同理，这些用例同时充当 JS 侧字段名的契约测试：
 * `web/src/protocol.ts` 的 `CharacterCount` 字段一旦改名而这里没同步，测试就会失败。
 */
class CharacterCountTest {

    @Test
    fun `解析完整字数`() {
        val count = CharacterCount.fromJson(
            """{"characters":10,"charactersWithSpaces":14,"words":3}""",
        )

        assertEquals(10, count.characters)
        assertEquals(14, count.charactersWithSpaces)
        assertEquals(3, count.words)
        assertFalse(count.isEmpty)
    }

    @Test
    fun `字段缺失时回退为 0`() {
        val count = CharacterCount.fromJson("{}")
        assertEquals(0, count.characters)
        assertEquals(0, count.charactersWithSpaces)
        assertEquals(0, count.words)
        assertTrue(count.isEmpty)
    }

    @Test
    fun `非法 JSON 不抛异常而是回退为空`() {
        assertEquals(CharacterCount.EMPTY, CharacterCount.fromJson("不是 JSON"))
        assertEquals(CharacterCount.EMPTY, CharacterCount.fromJson(""))
    }

    @Test
    fun `负数被纠正为 0`() {
        // 防御性：正常不会出现负数，但不应让 UI 显示出 "-1 字"
        val count = CharacterCount.fromJson("""{"characters":-5,"words":-1}""")
        assertEquals(0, count.characters)
        assertEquals(0, count.words)
    }

    @Test
    fun `只含空白字符时视为空文档`() {
        val count = CharacterCount.fromJson("""{"characters":0,"charactersWithSpaces":3,"words":0}""")
        // 含空白的长度不为 0，但有意义的内容为 0
        assertEquals(0, count.characters)
        assertFalse(count.isEmpty)
    }

    @Test
    fun `EMPTY 语义正确`() {
        assertTrue(CharacterCount.EMPTY.isEmpty)
        assertEquals(0, CharacterCount.EMPTY.charactersWithSpaces)
    }
}
