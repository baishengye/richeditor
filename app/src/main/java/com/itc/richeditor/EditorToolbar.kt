package com.itc.richeditor

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton
import com.itc.richeditorv2.EditorCommand
import com.itc.richeditorv2.EditorEventListener
import com.itc.richeditorv2.EditorSnapshot
import com.itc.richeditorv2.EditorToolbarState
import com.itc.richeditorv2.RichEditorView

/**
 * 示例工具栏 —— 注意这是 **Demo 应用自己的 UI**，不属于 `richeditorv2` 库。
 *
 * 放到 app 模块是有意的：工具栏的长相、包含哪些按钮、更多菜单里放什么，
 * 都是产品决策而不是编辑器能力，库不该替宿主决定。这里给出一份可直接抄走、
 * 也可随意改的参考实现。
 *
 * ## 核心语义：按钮是「选中」而不是「点击」
 * 四个按钮（B / I / U / S）反映的是**光标/选区所在位置的格式**，不是"上一次点了什么"。具体表现：
 *  - 光标移进一段加粗文字 → 加粗按钮自动变亮；
 *  - 撤销/重做导致格式变化 → 按钮跟随；
 *  - 选区跨越多段不同格式 → 按编辑器给出的状态显示。
 *
 * 因此这里**绝不在点击时翻转按钮状态**：点击只调 [RichEditorView.perform] 发命令，
 * 按钮视觉状态一律来自 [EditorSnapshot] 推送，映射规则用库提供的 [EditorToolbarState]。
 *
 * ## 订阅方式
 * 通过 [RichEditorView.addListener] 订阅，不会覆盖宿主自己设置的 `listener`，
 * 因此宿主仍可同时监听 onContentChanged 等事件。
 */
class EditorToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点击「全屏」按钮。为 null 时该按钮隐藏——全屏怎么实现取决于宿主页面结构 */
    var onExpandClick: (() -> Unit)? = null
        set(value) {
            field = value
            expandButton?.visibility = if (value == null) GONE else VISIBLE
        }

    /** 接管链接输入。为 null 时使用内置对话框 */
    var onRequestLink: ((currentUrl: String?) -> Unit)? = null

    private var editor: RichEditorView? = null
    private var expandButton: ImageButton? = null
    private var overflow: PopupMenu? = null

    private val toggleButtons = LinkedHashMap<Int, EditorCommand>()
    private val menuCommands = LinkedHashMap<Int, EditorCommand?>()
    private var lastSnapshot: EditorSnapshot = EditorSnapshot.EMPTY

    /** 状态观察者，工具栏自持，避免与宿主的 listener 互相覆盖 */
    private val stateObserver = object : EditorEventListener {
        override fun onStateChanged(state: EditorSnapshot) {
            render(state)
            refreshOverflowMenu(state)
        }

        override fun onReady() {
            editor?.let { render(it.lastState) }
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 整体不参与焦点获取，否则点击会收起软键盘并丢失 WebView 内选区
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        LayoutInflater.from(context).inflate(R.layout.editor_toolbar, this, true)

        bindToggleButton(R.id.btn_bold, EditorCommand.Bold)
        bindToggleButton(R.id.btn_italic, EditorCommand.Italic)
        bindToggleButton(R.id.btn_underline, EditorCommand.Underline)
        bindToggleButton(R.id.btn_strike, EditorCommand.Strike)

        // 对齐：三个按钮同属一个单选组，互斥性由 EditorSnapshot.align 是单值天然保证
        bindToggleButton(R.id.btn_align_left, EditorCommand.AlignLeft)
        bindToggleButton(R.id.btn_align_center, EditorCommand.AlignCenter)
        bindToggleButton(R.id.btn_align_right, EditorCommand.AlignRight)

        // 撤销/重做：动作型，没有选中态，但会随历史栈启用/禁用
        bindToggleButton(R.id.btn_undo, EditorCommand.Undo)
        bindToggleButton(R.id.btn_redo, EditorCommand.Redo)

        findViewById<ImageButton>(R.id.btn_more).setOnClickListener { showOverflow(it) }

        expandButton = findViewById<ImageButton>(R.id.btn_expand).apply {
            visibility = if (onExpandClick == null) GONE else VISIBLE
            setOnClickListener { onExpandClick?.invoke() }
        }

        registerMenuCommands()
        render(EditorSnapshot.EMPTY)
    }

    /** 绑定编辑器：订阅状态并让按钮把命令发给它。重复绑定会先解绑 */
    fun bindTo(editor: RichEditorView) {
        unbind()
        this.editor = editor
        editor.addListener(stateObserver)
        render(editor.lastState)
    }

    fun unbind() {
        editor?.removeListener(stateObserver)
        editor = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 视图移除后继续持有编辑器会泄漏
        unbind()
    }

    private fun bindToggleButton(id: Int, command: EditorCommand) {
        toggleButtons[id] = command
        findViewById<MaterialButton>(id).apply {
            // MaterialButton 没有对应的 XML 属性来开启 checkable，只能在代码里设。
            // 不设的话 isChecked 不会驱动 state_checked，选中态的背景/图标色切换就失效。
            // 动作型命令（撤销/重做）本身没有选中语义，保持非 checkable 以避免误导。
            isCheckable = command.isToggle
            setOnClickListener {
                // 只发命令，不改按钮状态
                editor?.perform(command)
            }
        }
    }

    private fun registerMenuCommands() {
        menuCommands[R.id.menu_paragraph] = EditorCommand.Paragraph
        menuCommands[R.id.menu_heading1] = EditorCommand.Heading1
        menuCommands[R.id.menu_heading2] = EditorCommand.Heading2
        menuCommands[R.id.menu_heading3] = EditorCommand.Heading3
        menuCommands[R.id.menu_bullet_list] = EditorCommand.BulletList
        menuCommands[R.id.menu_ordered_list] = EditorCommand.OrderedList
        menuCommands[R.id.menu_blockquote] = EditorCommand.Blockquote
        menuCommands[R.id.menu_code_block] = EditorCommand.CodeBlock
        menuCommands[R.id.menu_code] = EditorCommand.Code
        menuCommands[R.id.menu_link] = null // 需要输入 URL，特殊处理
        menuCommands[R.id.menu_unlink] = EditorCommand.UnsetLink
        menuCommands[R.id.menu_divider] = EditorCommand.HorizontalRule
        menuCommands[R.id.menu_undo] = EditorCommand.Undo
        menuCommands[R.id.menu_redo] = EditorCommand.Redo
        menuCommands[R.id.menu_select_all] = EditorCommand.SelectAll
        menuCommands[R.id.menu_clear] = EditorCommand.ClearContent
    }

    /** 把快照映射到按钮的选中/可用状态 */
    private fun render(state: EditorSnapshot) {
        lastSnapshot = state
        toggleButtons.forEach { (id, command) ->
            val button = findViewById<MaterialButton>(id) ?: return@forEach
            // 关键：按钮状态完全由快照决定
            if (button.isCheckable) {
                button.isChecked = EditorToolbarState.isSelected(state, command)
            }
            button.isEnabled = EditorToolbarState.isEnabled(state, command)
        }
    }

    private fun refreshOverflowMenu(state: EditorSnapshot) {
        val menu = overflow?.menu ?: return
        for (index in 0 until menu.size()) {
            applyMenuItemState(menu.getItem(index), state)
        }
    }

    private fun applyMenuItemState(item: MenuItem, state: EditorSnapshot) {
        val command = menuCommands[item.itemId]
        if (command == null) {
            item.isEnabled = when (item.itemId) {
                R.id.menu_unlink -> state.editable && state.link != null
                else -> state.editable
            }
            return
        }
        item.isEnabled = EditorToolbarState.isEnabled(state, command)
        if (item.isCheckable) {
            item.isChecked = EditorToolbarState.isSelected(state, command)
        }
    }

    private fun showOverflow(anchor: View) {
        val target = editor ?: return
        val popup = PopupMenu(context, anchor)
        popup.menuInflater.inflate(R.menu.editor_overflow, popup.menu)

        val menu: Menu = popup.menu
        for (index in 0 until menu.size()) {
            applyMenuItemState(menu.getItem(index), lastSnapshot)
        }

        popup.setOnMenuItemClickListener { item -> handleMenuItem(target, item) }
        overflow = popup
        popup.setOnDismissListener { overflow = null }
        popup.show()
    }

    private fun handleMenuItem(target: RichEditorView, item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_link) {
            requestLink(target)
            return true
        }
        val command = menuCommands[item.itemId] ?: return false
        target.perform(command)
        return true
    }

    private fun requestLink(target: RichEditorView) {
        val current = lastSnapshot.link
        val handler = onRequestLink
        if (handler != null) {
            handler(current)
        } else {
            showBuiltInLinkDialog(target, current)
        }
    }

    /** 内置链接输入对话框，保证开箱可用；宿主可用 [onRequestLink] 替换 */
    private fun showBuiltInLinkDialog(target: RichEditorView, currentUrl: String?) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = FrameLayout(context).apply { setPadding(pad, pad / 2, pad, 0) }

        val input = EditText(context).apply {
            hint = resources.getString(R.string.link_dialog_hint)
            setText(currentUrl.orEmpty())
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        container.addView(
            input,
            FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )

        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.link_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                target.setLink(input.text.toString().trim())
            }
            .setNegativeButton(R.string.dialog_cancel, null)

        if (currentUrl != null) {
            builder.setNeutralButton(R.string.link_dialog_remove) { _, _ -> target.setLink("") }
        }
        builder.create().show()
    }
}
