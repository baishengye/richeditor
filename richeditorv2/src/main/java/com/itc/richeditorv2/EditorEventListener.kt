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

    /**
     * 点击了编辑器内的链接。
     *
     * 这是**轻点**（click）触发，不是长按选择。此时 WebView 不会自主导航，
     * 需要宿主自己决定怎么处理，例如：
     * ```kotlin
     * override fun onLinkClick(url: String) {
     *     startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
     * }
     * ```
     * 如果什么都不做，链接就是「点了没反应」——这通常不是用户想要的。
     *
     * 注意：链接地址来自文档内容，可能是任意 scheme（包括 javascript:）。
     * 若要真的跳转，建议先校验 scheme 白名单，避免被恶意内容利用。
     */
    fun onLinkClick(url: String) = Unit

    /**
     * 字数统计变化。
     *
     * 每次内容变化（含 setContent）都会推送，但会去重，不会重复推相同的数值。
     */
    fun onCharacterCountChanged(count: CharacterCount) = Unit

    /** 内部错误，包含 JS 侧抛出的原因，便于排查 */
    fun onError(message: String) = Unit
}
