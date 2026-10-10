package com.itc.richeditorv2

/**
 * 可由 Kotlin 触发的编辑器命令。
 *
 * 每一项对应 `web/src/protocol.ts` 中 `SIMPLE_COMMANDS` 白名单里的一条，
 * 或 `commands.ts` 里的别名。[jsName] 必须与 JS 侧完全一致。
 *
 * 设计成枚举而不是裸字符串，是为了让"两侧名字写错"这种问题在编译期就暴露。
 *
 * 注意：这些命令都是**切换型（toggle）**语义——执行结果取决于当前选区状态，
 * 因此工具栏按钮的选中态应当来自 [EditorSnapshot] 的推送，而不是自己翻转。
 */
enum class EditorCommand(val jsName: String) {

    /** 加粗，对应 [EditorSnapshot.bold] */
    Bold("toggleBold"),

    /** 斜体，对应 [EditorSnapshot.italic] */
    Italic("toggleItalic"),

    /** 下划线，对应 [EditorSnapshot.underline] */
    Underline("toggleUnderline"),

    /** 删除线，对应 [EditorSnapshot.strike] */
    Strike("toggleStrike"),

    /** 行内代码 */
    Code("toggleCode"),

    /**
     * 标题。注意 [EditorSnapshot.heading] 是 Int（0=无，1..3=级别），一个按钮表达不了三态，
     * 所以拆成三个独立命令，UI 上也是三个按钮。
     */
    Heading1("toggleHeading1"),
    Heading2("toggleHeading2"),
    Heading3("toggleHeading3"),

    /** 正文段落，对应 [EditorSnapshot.heading] == 0 */
    Paragraph("setParagraph"),

    /**
     * 左对齐。
     *
     * 对齐是**单选组**：三个按钮同一时刻只有一个亮，由 [EditorSnapshot.align] 驱动。
     * 对应的 JS 命令是 `setTextAlign(alignment)` 的别名（见 web/src/commands.ts）。
     */
    AlignLeft("alignLeft"),

    /** 居中对齐，对应 [EditorSnapshot.align] == [TextAlign.Center] */
    AlignCenter("alignCenter"),

    /** 右对齐，对应 [EditorSnapshot.align] == [TextAlign.Right] */
    AlignRight("alignRight"),

    /** 无序列表，对应 [EditorSnapshot.bulletList] */
    BulletList("toggleBulletList"),

    /** 有序列表，对应 [EditorSnapshot.orderedList] */
    OrderedList("toggleOrderedList"),

    /** 引用，对应 [EditorSnapshot.blockquote] */
    Blockquote("toggleBlockquote"),

    /** 代码块，对应 [EditorSnapshot.codeBlock] */
    CodeBlock("toggleCodeBlock"),

    /** 分割线（一次性插入，不体现选中态） */
    HorizontalRule("setHorizontalRule"),

    /** 强制换行（一次性插入，不体现选中态） */
    HardBreak("setHardBreak"),

    /** 移除链接 */
    UnsetLink("unsetLink"),

    /** 撤销，可用性见 [EditorSnapshot.canUndo] */
    Undo("undo"),

    /** 重做，可用性见 [EditorSnapshot.canRedo] */
    Redo("redo"),

    /** 全选 */
    SelectAll("selectAll"),

    /**
     * 删除当前选区。
     *
     * 最典型的用途是删除选中的图片（图片被选中时，删除选区就等于删除图片）。
     * 属于动作型命令，没有选中态。
     */
    DeleteSelection("deleteSelection"),

    /** 清空内容 */
    ClearContent("clearContent"),
    ;

    /**
     * 该命令在 UI 上是否应表现为"可选中"的开关。
     *
     * 插入型命令（分割线、换行）是瞬时动作，没有选中态，
     * 工具栏应把它们做成普通按钮而不是可勾选按钮。
     */
    val isToggle: Boolean
        get() = when (this) {
            HorizontalRule, HardBreak, UnsetLink, Undo, Redo, SelectAll, ClearContent,
            DeleteSelection,
            -> false

            else -> true
        }
}
