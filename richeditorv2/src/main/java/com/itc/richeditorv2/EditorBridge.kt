package com.itc.richeditorv2

import android.os.Handler
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * 注入到 WebView 的 JS 桥（`window.AndroidBridge`）。
 *
 * 注意两个容易踩的点：
 *  1. `@JavascriptInterface` 标注的方法**运行在 WebView 的 JavaBridge 线程**，不是主线程。
 *     所以这里统一 parse + 切主线程，保证对外回调始终在主线程。
 *  2. 方法名必须与 `web/src/protocol.ts` 的 `BridgeEvent` 完全一致，且参数只能是 String。
 *     R8 会按名字反射查找这些方法，必须配置 keep 规则（见 `src/main/keepRules/rules.keep`）。
 */
internal class EditorBridge(
    private val mainHandler: Handler,
    private val listener: EditorEventListener,
    private val density: Float,
) {

    @JavascriptInterface
    fun onReady(payload: String) {
        mainHandler.post { listener.onReady() }
    }

    @JavascriptInterface
    fun onUpdate(json: String) {
        // 内容 JSON 可能较大，先在桥线程留作字符串，交给主线程原样透出
        mainHandler.post { listener.onContentChanged(json) }
    }

    @JavascriptInterface
    fun onSelectionChange(json: String) {
        // 在主线程解析：对象很小，但避免让调用方拿到半成品状态
        mainHandler.post { listener.onStateChanged(EditorSnapshot.fromJson(json)) }
    }

    @JavascriptInterface
    fun onHeightChanged(heightCssPx: String) {
        // JS 上报的是 CSS 像素，换算成设备像素，调用方可直接用于 layoutParams
        val cssPx = heightCssPx.toFloatOrNull() ?: return
        val devicePx = (cssPx * density).toInt()
        mainHandler.post { listener.onHeightChanged(devicePx) }
    }

    @JavascriptInterface
    fun onFocusChange(focused: String) {
        val value = focused.toBooleanStrictOrNull() ?: (focused == "1")
        mainHandler.post { listener.onFocusChanged(value) }
    }

    /**
     * JS 侧拦截了链接点击（已 preventDefault，WebView 不会导航），上报给宿主决定怎么处理。
     *
     * 这里直接透传原始字符串：链接地址可能很长，且主线程不需要做任何解析。
     */
    @JavascriptInterface
    fun onLinkClick(url: String) {
        mainHandler.post { listener.onLinkClick(url) }
    }

    @JavascriptInterface
    fun onCharacterCountChanged(json: String) {
        // 在主线程解析：对象只有三个 int，开销可忽略
        mainHandler.post { listener.onCharacterCountChanged(CharacterCount.fromJson(json)) }
    }

    @JavascriptInterface
    fun onError(message: String) {
        mainHandler.post {
            listener.onError(message)
        }
    }

    /** 供 [RichEditorView] 内部上报非 JS 来源的错误（例如资源加载失败） */
    fun reportError(message: String) {
        Log.w(TAG, message)
        mainHandler.post { listener.onError(message) }
    }

    companion object {
        private const val TAG = "RichEditorBridge"
    }
}
