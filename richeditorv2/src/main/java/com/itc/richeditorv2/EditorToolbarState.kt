package com.itc.richeditorv2

/**
 * 把 [EditorSnapshot] 映射为工具栏按钮的视觉状态。
 *
 * ## 为什么需要它
 * 这类工具栏最常见的错误写法是：点击按钮时自己翻转 `isChecked`。
 * 那样会立刻产生偏差——真实状态由编辑器决定，不由按钮决定。典型失配场景：
 *  - 光标移入一段加粗文字，加粗按钮不会自动变亮；
 *  - 撤销/重做之后按钮状态与文档不符；
 *  - 选区跨越多种格式时按钮状态无意义。
 *
 * 正确做法是：**按钮只反映状态，不自己改状态**。点击时发送命令，
 * 状态一律以 [EditorEventListener.onStateChanged] 推送的快照为准。
 * 本对象就是"快照 → 按钮"的映射规则，集中在这里便于测试，也避免各处重复实现。
 *
 * ## 用法
 * ```kotlin
 * editor.listener = object : EditorEventListener {
 *     override fun onStateChanged(state: EditorSnapshot) {
 *         boldButton.isChecked = EditorToolbarState.isSelected(state, EditorCommand.Bold)
 *         boldButton.isEnabled = EditorToolbarState.isEnabled(state, EditorCommand.Bold)
 *     }
 * }
 * ```
 */
object EditorToolbarState {

    /**
     * 该命令在给定状态下是否应表现为"已选中"。
     *
     * 插入型与动作型命令（分割线、撤销、全选…）没有选中语义，恒返回 false；
     * 这类按钮应当用普通按钮呈现，而不是可勾选按钮。
     */
    fun isSelected(state: EditorSnapshot, command: EditorCommand): Boolean = when (command) {
        EditorCommand.Bold -> state.bold
        EditorCommand.Italic -> state.italic
        EditorCommand.Underline -> state.underline
        EditorCommand.Strike -> state.strike
        EditorCommand.Code -> state.code

        // 标题是一组互斥选项：快照里 heading 只有一个值，
        // 因此这里天然保证了"同时只有一个标题按钮被选中"，无需按钮组干预。
        EditorCommand.Heading1 -> state.heading == 1
        EditorCommand.Heading2 -> state.heading == 2
        EditorCommand.Heading3 -> state.heading == 3
        // "正文"与标题同组互斥
        EditorCommand.Paragraph -> state.heading == 0

        // 对齐三按钮同组互斥：快照里 align 是单值，天然保证同时只有一个亮
        EditorCommand.AlignLeft -> state.align == TextAlign.Left
        EditorCommand.AlignCenter -> state.align == TextAlign.Center
        EditorCommand.AlignRight -> state.align == TextAlign.Right

        EditorCommand.BulletList -> state.bulletList
        EditorCommand.OrderedList -> state.orderedList
        EditorCommand.Blockquote -> state.blockquote
        EditorCommand.CodeBlock -> state.codeBlock

        // 动作型 / 插入型命令没有选中态
        EditorCommand.HorizontalRule,
        EditorCommand.HardBreak,
        EditorCommand.UnsetLink,
        EditorCommand.Undo,
        EditorCommand.Redo,
        EditorCommand.SelectAll,
        EditorCommand.ClearContent,
        -> false
    }

    /**
     * 该命令当前是否可用。
     *
     * 目前只有撤销/重做会随历史栈变化而启用/禁用；
     * 其余命令在只读模式下整体禁用。
     */
    fun isEnabled(state: EditorSnapshot, command: EditorCommand): Boolean = when (command) {
        EditorCommand.Undo -> state.editable && state.canUndo
        EditorCommand.Redo -> state.editable && state.canRedo
        else -> state.editable
    }
}
