package com.itc.richeditorv2

/**
 * 编辑器事件回调。
 *
 * 所有回调都在**主线程**触发，可以直接在实现里更新 UI，无需再自己切线程。
 *
 * 每个方法都有默认空实现，按需覆写即可。
 */
interface EditorEventListener {

    /**
     * 编辑器就绪。
     *
     * 只有在这个回调之后，[RichEditorView.setContent] 之类的内容操作才会立即生效；
     * 在此之前调用会被排队，就绪后自动补发（见 [RichEditorView] 的 pending 队列）。
     */
    fun onReady() = Unit

    /**
     * 内容发生变化（用户输入、或被设置了 emitUpdate 的内容）。
     *
     * @param json ProseMirror 文档 JSON，可直接持久化后回传给 [RichEditorView.setContent]。
     */
    fun onContentChanged(json: String) = Unit

    /**
     * 光标/选区状态变化，或文档状态变化。
     *
     * 这就是"按钮选中态"的数据源：把它映射到工具栏按钮的 isChecked 即可，
     * 无需在点击时自己翻转按钮状态（那样会和真实选区状态脱节）。
     */
    fun onStateChanged(state: EditorSnapshot) = Unit

    /**
     * 内容高度变化。
     *
     * @param heightPx **设备像素**（已按屏幕密度换算），可直接赋给 `layoutParams.height`。
     */
    fun onHeightChanged(heightPx: Int) = Unit

    /** 焦点变化 */
    fun onFocusChanged(focused: Boolean) = Unit

    /** 内部错误，包含 JS 侧抛出的原因，便于排查 */
    fun onError(message: String) = Unit
}
