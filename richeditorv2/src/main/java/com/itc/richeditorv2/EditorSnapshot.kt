package com.itc.richeditorv2

import org.json.JSONObject

/** 段落对齐方式，与 `web/src/protocol.ts` 的 `AlignValue` 一致。 */
enum class TextAlign(val jsValue: String) {
    Left("left"),
    Center("center"),
    Right("right"),
    ;

    companion object {
        /** 未知值一律归一化为 [Left]：未设置对齐的块本来就是左对齐 */
        fun fromJs(value: String?): TextAlign =
            // 用 values() 而不是 entries：entries 是 Kotlin 1.9 才有的 API，
            // 用它会编译出 kotlin/enums/EnumEntries 的引用，Kotlin 1.7.20 的 stdlib 没有这个类。
            values().firstOrNull { it.jsValue == value } ?: Left
    }
}

/**
 * 编辑器当前状态快照，对应 `web/src/snapshot.ts` 的 `EditorSnapshot`。
 *
 * 这里的每个字段都是"当前选区/光标处是否生效"的**状态**，而不是"刚刚点了某个按钮"。
 * 工具栏应当直接把它映射到按钮的选中态（例如 [bold] → 加粗按钮 isChecked），
 * 这样光标移入加粗文字、或撤销/重做之后，按钮状态都会自动跟随，不需要手动维护。
 */
data class EditorSnapshot(
    /** 当前块级节点的对齐方式；未设置对齐属性时归一化为 [TextAlign.Left] */
    val align: TextAlign = TextAlign.Left,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** 行内代码 */
    val code: Boolean = false,
    /** 0 表示非标题，1..6 表示对应级别 */
    val heading: Int = 0,
    val bulletList: Boolean = false,
    val orderedList: Boolean = false,
    val blockquote: Boolean = false,
    val codeBlock: Boolean = false,
    /** 当前链接地址，无则为 null */
    val link: String? = null,
    /** 当前选区是否选中了一张图片 */
    val image: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val editable: Boolean = true,
    /** 文档是否为空，可用于显示占位提示 */
    val empty: Boolean = true,
) {
    /** 是否为有序/无序列表中的任意一种 */
    val inList: Boolean get() = bulletList || orderedList

    /** 用于日志与测试的可读输出 */
    override fun toString(): String =
        "EditorSnapshot(align=${align.jsValue}, bold=$bold, italic=$italic, underline=$underline, " +
            "strike=$strike, heading=$heading, list=$inList, quote=$blockquote, code=$codeBlock, " +
            "link=$link, image=$image, undo=$canUndo, redo=$canRedo, " +
            "editable=$editable, empty=$empty)"

    companion object {
        val EMPTY = EditorSnapshot()

        /**
         * 解析 JS 上报的 JSON。
         *
         * 容错策略：字段缺失或类型不符时退回默认值，整体不抛异常。
         * 原因是这个快照只驱动 UI 显示，任何解析失败都不该让编辑器功能中断。
         */
        fun fromJson(json: String): EditorSnapshot {
            return try {
                val obj = JSONObject(json)
                EditorSnapshot(
                    align = TextAlign.fromJs(obj.optString("align").takeIf { it.isNotEmpty() }),
                    bold = obj.optBoolean("bold", false),
                    italic = obj.optBoolean("italic", false),
                    underline = obj.optBoolean("underline", false),
                    strike = obj.optBoolean("strike", false),
                    code = obj.optBoolean("code", false),
                    heading = obj.optInt("heading", 0),
                    bulletList = obj.optBoolean("bulletList", false),
                    orderedList = obj.optBoolean("orderedList", false),
                    blockquote = obj.optBoolean("blockquote", false),
                    codeBlock = obj.optBoolean("codeBlock", false),
                    link = if (obj.isNull("link")) null else obj.optString("link").takeIf { it.isNotEmpty() },
                    image = obj.optBoolean("image", false),
                    canUndo = obj.optBoolean("canUndo", false),
                    canRedo = obj.optBoolean("canRedo", false),
                    editable = obj.optBoolean("editable", true),
                    empty = obj.optBoolean("empty", true),
                )
            } catch (e: Exception) {
                EMPTY
            }
        }
    }
}
