package com.itc.richeditorv2

import org.json.JSONObject

/**
 * 字数统计，对应 `web/src/protocol.ts` 的 `CharacterCount`。
 *
 * 中文场景下「字数」通常指字符数，所以 [characters] 是主指标；
 * 另外两个值一并给出，宿主想显示哪个自己选。
 *
 * 注意统计的是**纯文本**：图片、分割线等非文本节点不计入。
 */
data class CharacterCount(
    /** 字符数（**不包含**空白字符），中文场景下的「字数」 */
    val characters: Int = 0,
    /** 字符数（包含空白字符） */
    val charactersWithSpaces: Int = 0,
    /** 西文单词数 */
    val words: Int = 0,
) {
    /** 文档是否没有任何文本 */
    val isEmpty: Boolean get() = charactersWithSpaces == 0

    override fun toString(): String =
        "CharacterCount(characters=$characters, withSpaces=$charactersWithSpaces, words=$words)"

    companion object {
        val EMPTY = CharacterCount()

        /**
         * 解析 JS 上报的 JSON。
         *
         * 与 [EditorSnapshot.fromJson] 相同的容错策略：字段缺失或非法时退回 0，绝不抛异常。
         * 字数只用于显示，任何解析问题都不该影响编辑器本身。
         */
        fun fromJson(json: String): CharacterCount {
            return try {
                val obj = JSONObject(json)
                CharacterCount(
                    characters = obj.optInt("characters", 0).coerceAtLeast(0),
                    charactersWithSpaces = obj.optInt("charactersWithSpaces", 0).coerceAtLeast(0),
                    words = obj.optInt("words", 0).coerceAtLeast(0),
                )
            } catch (e: Exception) {
                EMPTY
            }
        }
    }
}
