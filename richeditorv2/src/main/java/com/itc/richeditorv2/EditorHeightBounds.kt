package com.itc.richeditorv2

/**
 * 编辑器高度的上下限钳制规则。
 *
 * 单独抽出来是为了可测试：[RichEditorView.onMeasure] 需要仪器化测试才能验证，
 * 而"钳制规则本身"是纯计算，放这里就能用普通单元测试锁住。
 *
 * 约定与 [RichEditorView.minHeight] / [RichEditorView.maxHeight] 一致：
 * 小于等于 0 表示"该方向不限制"（对应 XML 属性不写时的默认值）。
 */
internal object EditorHeightBounds {

    /**
     * 把测量出来的高度钳制到 `[minHeight, maxHeight]` 之间。
     *
     * @param measured 测量得到的高度（设备像素）
     * @param minHeight 下限，`<= 0` 表示不限制
     * @param maxHeight 上限，`<= 0` 表示不限制
     *
     * 当下限大于上限（配置写反了）时，**以上限为准**：先抬到下限再压到上限，
     * 两者的顺序决定了这一点。这样至少不会出现"高度比上限还大"的结果。
     */
    fun clamp(measured: Int, minHeight: Int, maxHeight: Int): Int {
        var result = measured
        if (minHeight > 0 && result < minHeight) {
            result = minHeight
        }
        if (maxHeight > 0 && result > maxHeight) {
            result = maxHeight
        }
        return result
    }
}
