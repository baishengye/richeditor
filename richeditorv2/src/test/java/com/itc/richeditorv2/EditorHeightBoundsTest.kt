package com.itc.richeditorv2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EditorHeightBounds] 的钳制规则测试。
 *
 * `onMeasure` 本身要仪器化测试才能覆盖，所以这里锁的是**规则**：
 * 只要这几个边界行为不变，`onMeasure` 里就只是把测量结果送进来而已。
 *
 * 已知未覆盖的部分：[RichEditorView.readHeightBounds] 对 `android:minHeight` /
 * `android:maxHeight` 的读取，以及 WebView 在 `wrap_content` 下实际测出的高度，
 * 都需要真机/仪器化测试。规则本身已锁定，若真机表现不符，问题只可能在这两处。
 */
class EditorHeightBoundsTest {

    @Test
    fun `未设置上下限时原样返回`() {
        assertEquals(350, EditorHeightBounds.clamp(measured = 350, minHeight = 0, maxHeight = 0))
    }

    @Test
    fun `低于下限时抬到下限`() {
        assertEquals(200, EditorHeightBounds.clamp(measured = 40, minHeight = 200, maxHeight = 0))
    }

    @Test
    fun `高于上限时压到上限`() {
        assertEquals(400, EditorHeightBounds.clamp(measured = 1200, minHeight = 0, maxHeight = 400))
    }

    @Test
    fun `落在区间内时不动`() {
        assertEquals(300, EditorHeightBounds.clamp(measured = 300, minHeight = 200, maxHeight = 400))
    }

    @Test
    fun `恰好等于上界或下界时不动`() {
        assertEquals(200, EditorHeightBounds.clamp(measured = 200, minHeight = 200, maxHeight = 400))
        assertEquals(400, EditorHeightBounds.clamp(measured = 400, minHeight = 200, maxHeight = 400))
    }

    @Test
    fun `只设下限或只设上限`() {
        // 只设下限：0 高度会被抬起来（这正是"内容加载前不塌成一条线"所依赖的行为）
        assertEquals(200, EditorHeightBounds.clamp(measured = 0, minHeight = 200, maxHeight = 0))
        // 只设上限：小内容不受影响
        assertEquals(50, EditorHeightBounds.clamp(measured = 50, minHeight = 0, maxHeight = 400))
    }

    @Test
    fun `负值视为不限制`() {
        // 负数是非法配置，按"不限制"处理，而不是当成"高度为负"
        assertEquals(350, EditorHeightBounds.clamp(measured = 350, minHeight = -1, maxHeight = -1))
        assertEquals(350, EditorHeightBounds.clamp(measured = 350, minHeight = -100, maxHeight = -50))
    }

    @Test
    fun `下限大于上限时以上限为准且不会返回负值`() {
        // 配置写反了（min > max）。此时先抬到下限、再压到上限，所以结果等于上限。
        // 关键是**不能**出现"比上限还大"的高度，否则 maxHeight 等于失效。
        val result = EditorHeightBounds.clamp(measured = 100, minHeight = 500, maxHeight = 300)
        assertEquals(300, result)

        // 即使测量值极小也一样
        assertEquals(300, EditorHeightBounds.clamp(measured = 0, minHeight = 500, maxHeight = 300))
    }

    @Test
    fun `测量值为 0 且不设限时保持 0`() {
        // WebView 在页面加载完成前可能测出 0；不设下限就应该保持 0，不能被"修正"成别的值
        assertEquals(0, EditorHeightBounds.clamp(measured = 0, minHeight = 0, maxHeight = 0))
    }
}
