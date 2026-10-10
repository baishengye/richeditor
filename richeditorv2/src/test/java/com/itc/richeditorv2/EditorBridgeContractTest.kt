package com.itc.richeditorv2

import android.webkit.JavascriptInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 JS 桥的方法名与契约常量完全对应。
 *
 * ## 为什么需要这个测试
 * JS → Kotlin 的调用是**按方法名反射查找**的（WebView 的 `@JavascriptInterface` 机制，
 * 再叠加 R8 的 keep 规则）。这条链路上有三处必须同时改到：
 *
 *  1. `web/src/protocol.ts` 的 `BridgeEvent` —— JS 侧的发起方
 *  2. `RichEditorContract` 的 `EVENT_*` 常量 —— Kotlin 侧的声明
 *  3. `EditorBridge` 上带 `@JavascriptInterface` 的方法 —— Kotlin 侧的接收方
 *
 * 漏改任何一处都**不会有编译错误**，运行时也只是"这个事件静默地不再触发"，
 * 属于很难排查的问题（这个库已经因为同类问题踩过一次坑）。
 * 这个测试把第 2、3 项锁在一起，把问题提前到构建阶段。
 *
 * 第 1 项（JS 侧）无法在 Kotlin 单测里读取 TS 源码来校验，只能靠约定与 review；
 * 它由浏览器/真机上的端到端验证覆盖。
 */
class EditorBridgeContractTest {

    @Test
    fun `桥接方法与契约常量一一对应`() {
        val contractEvents = RichEditorContract::class.java.declaredFields
            .filter { it.name.startsWith("EVENT_") }
            .map { it.get(null) as String }
            .toSet()

        val bridgeMethods = EditorBridge::class.java.declaredMethods
            .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
            .map { it.name }
            .toSet()

        assertEquals(
            "EditorBridge 上的 @JavascriptInterface 方法应与 RichEditorContract 的 EVENT_* 一一对应，" +
                "新增/改名时必须两边同时改",
            contractEvents,
            bridgeMethods,
        )
    }

    @Test
    fun `事件名符合 WebView 回调的命名约定`() {
        RichEditorContract::class.java.declaredFields
            .filter { it.name.startsWith("EVENT_") }
            .forEach { field ->
                val value = field.get(null) as String
                assertTrue("${field.name} 应以 on 开头：$value", value.startsWith("on"))
                assertTrue(
                    "${field.name} 应为合法的 JS 标识符：$value",
                    value.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")),
                )
            }
    }
}
