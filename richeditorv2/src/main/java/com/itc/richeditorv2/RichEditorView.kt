package com.itc.richeditorv2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 基于 tiptap（WebView 内运行）的富文本编辑器控件。
 *
 * ## 基本用法
 * ```kotlin
 * val editor = RichEditorView(context)
 * editor.listener = object : EditorEventListener {
 *     override fun onStateChanged(state: EditorSnapshot) {
 *         // 关键：按钮选中态完全由这里驱动，不要在点击时手动翻转
 *         boldButton.isChecked = state.bold
 *     }
 *     override fun onHeightChanged(heightPx: Int) {
 *         editor.layoutParams = editor.layoutParams.apply { height = heightPx }
 *     }
 * }
 * editor.setContent(savedJson)   // 就绪前调用会被排队，就绪后自动补发
 * ```
 *
 * ## 线程
 * 所有公开方法都应在主线程调用（与普通 View 一致），所有回调也都在主线程触发。
 *
 * ## 关于"就绪"
 * 编辑器在 WebView 里异步创建，`onReady` 之前无法接受内容与命令。
 * 因此 [setContent] / [setEditable] / [setPlaceholder] 会走排队机制；
 * 而 [perform] / [getContentJson] 这类依赖编辑器实例的操作在就绪前直接返回失败。
 */
class RichEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    /**
     * 编辑器事件回调（主监听器），默认空实现。
     *
     * 如果需要多个观察者（例如宿主自己的工具栏与业务逻辑各订阅一份），
     * 用 [addListener] / [removeListener]，不要互相覆盖这个字段。
     */
    var listener: EditorEventListener = object : EditorEventListener {}

    /**
     * 点击编辑器内链接时的回调。
     * 默认行为是拦下导航（不回调则链接不会跳转），避免 WebView 被链接带走去加载任意页面。
     */
    var onExternalLinkClick: ((url: String) -> Unit)? = null

    /**
     * 自定义资源拦截，在 assets 处理之前被调用。
     *
     * 返回非 null 表示“这个请求我来提供”，返回 null 则继续走默认的 assets 处理。
     *
     * 这是显示**本地图片**的关键扩展点。典型用法：宿主从相册选图后把文件拷到私有目录，
     * 然后注册一个前缀，把 `https://appassets.androidplatform.net/user/<name>` 映射到文件字节流。
     * 这样图片在 HTML 里就是一个普通的 https 地址，不需要开 `allowFileAccess`，
     * 也不受 mixed content 限制（页面本身也是 https）。
     *
     * ```kotlin
     * editor.onInterceptRequest = { request ->
     *     val path = request.url.path ?: return@onInterceptRequest null
     *     if (!path.startsWith("/user/")) return@onInterceptRequest null
     *     val file = File(appDir, path.removePrefix("/user/"))
     *     if (!file.exists()) return@onInterceptRequest null
     *     WebResourceResponse("image/jpeg", null, file.inputStream())
     * }
     * ```
     */
    var onInterceptRequest: ((request: WebResourceRequest) -> WebResourceResponse?)? = null

    /** 编辑器是否已就绪 */
    var isEditorReady: Boolean = false
        private set

    /**
     * 最近一次收到的状态快照。
     *
     * 工具栏初次绑定、或宿主想把按钮状态立即对齐时可以直接读它，
     * 不必等下一次推送。
     */
    var lastState: EditorSnapshot = EditorSnapshot.EMPTY
        private set

    /**
     * 最近一次收到的字数统计。
     *
     * 与 [lastState] 同理：绑定界面前或重建后想立即拿到当前值，可以直接读它。
     */
    var lastCount: CharacterCount = CharacterCount.EMPTY
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density: Float = resources.displayMetrics.density

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(RichEditorContract.ASSET_DOMAIN)
        .addPathHandler(
            RichEditorContract.ASSET_PREFIX,
            WebViewAssetLoader.AssetsPathHandler(context.applicationContext),
        )
        .build()

    private val bridge = EditorBridge(mainHandler, object : EditorEventListener {
        override fun onReady() {
            handleReady()
        }

        override fun onContentChanged(json: String) {
            dispatch { it.onContentChanged(json) }
        }

        override fun onStateChanged(state: EditorSnapshot) {
            lastState = state
            dispatch { it.onStateChanged(state) }
        }

        override fun onHeightChanged(heightPx: Int) {
            dispatch { it.onHeightChanged(heightPx) }
        }

        override fun onFocusChanged(focused: Boolean) {
            dispatch { it.onFocusChanged(focused) }
        }

        override fun onLinkClick(url: String) {
            dispatch { it.onLinkClick(url) }
        }

        override fun onCharacterCountChanged(count: CharacterCount) {
            lastCount = count
            dispatch { it.onCharacterCountChanged(count) }
        }

        override fun onError(message: String) {
            dispatch { it.onError(message) }
        }
    }, density)

    // 就绪前排队的操作，onReady 后依序补发
    private var pendingContent: String? = null
    private var pendingEditable: Boolean? = null
    private var pendingPlaceholder: String? = null

    private var readyTimeout: Runnable? = null
    private var destroyed = false

    /**
     * 额外监听器。与 [listener] 一起被派发，逐个回调。
     *
     * 用 CopyOnWriteArrayList：回调过程中允许增删监听器（例如宿主在 onReady 里挂载工具栏），
     * 不会触发 ConcurrentModificationException。
     */
    private val extraListeners = java.util.concurrent.CopyOnWriteArrayList<EditorEventListener>()

    init {
        configureWebView()
    }

    /** 添加一个额外监听器，重复添加会被忽略 */
    fun addListener(listener: EditorEventListener) {
        extraListeners.addIfAbsent(listener)
    }

    /** 移除额外监听器 */
    fun removeListener(listener: EditorEventListener) {
        extraListeners.remove(listener)
    }

    /**
     * 把事件派发给 [listener] 与所有 [extraListeners]。
     *
     * 单个监听器抛异常不会影响其他监听器——工具栏的渲染问题不该让宿主的业务回调丢失。
     */
    private inline fun dispatch(crossinline action: (EditorEventListener) -> Unit) {
        try {
            action(listener)
        } catch (e: Exception) {
            Log.e(TAG, "主监听器回调异常", e)
        }
        extraListeners.forEach { target ->
            try {
                action(target)
            } catch (e: Exception) {
                Log.e(TAG, "监听器回调异常", e)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        // 内容由本地 assets 提供，不需要文件系统与内容提供器访问，关掉可显著缩窄攻击面
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        // 固定 100%：否则系统字体缩放会让 WebView 内文字与原生控件尺寸脱节
        settings.textZoom = 100

        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        // 页面本身跑在 https://appassets.androidplatform.net，如果文档里引用了 http 图片，
        // 默认会被当作 mixed content 直接拦掉（图片显示为空白且无报错）。
        // 富文本场景下用户很可能粘贴 http 图片，所以这里放宽；
        // 更安全的做法是让图片走 https 或 data URL，此处属于体验与安全的权衡。
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setBackgroundColor(Color.TRANSPARENT)

        // 让编辑器能拿到焦点并唤起软键盘
        isFocusable = true
        isFocusableInTouchMode = true

        webViewClient = EditorWebViewClient()
        webChromeClient = WebChromeClient()

        addJavascriptInterface(bridge, RichEditorContract.BRIDGE_OBJECT)

        if (isAppDebuggable()) {
            // 可用 chrome://inspect 直接调试 WebView 内的 tiptap，与网页开发体验一致
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun isAppDebuggable(): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * 加载编辑器页面。构造后需要显式调用一次（而不是在 init 里自动加载），
     * 便于调用方在加载前先设置好 [listener] 与 [onExternalLinkClick]，避免丢事件。
     */
    fun loadEditor() {
        if (destroyed) {
            Log.w(TAG, "loadEditor() 在 destroy() 之后被调用，已忽略")
            return
        }
        Log.d(TAG, "loadEditor(): ${RichEditorContract.INDEX_URL}")
        scheduleReadyTimeout()
        loadUrl(RichEditorContract.INDEX_URL)
    }

    private fun scheduleReadyTimeout() {
        readyTimeout?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isEditorReady && !destroyed) {
                bridge.reportError(
                    "编辑器在 ${READY_TIMEOUT_MS}ms 内未就绪。常见原因：" +
                        "assets/editor 未随包发布、JS 执行异常、或 WebView 版本过低。",
                )
            }
        }
        readyTimeout = runnable
        mainHandler.postDelayed(runnable, READY_TIMEOUT_MS)
    }

    private fun handleReady() {
        Log.d(TAG, "handleReady(): 收到 JS 的 onReady")
        if (destroyed) {
            return
        }
        readyTimeout?.let { mainHandler.removeCallbacks(it) }
        readyTimeout = null
        isEditorReady = true

        // 依序补发排队操作：placeholder → editable → content
        pendingPlaceholder?.let { applyPlaceholder(it) }
        pendingPlaceholder = null
        pendingEditable?.let { applyEditable(it) }
        pendingEditable = null
        pendingContent?.let { applyContent(it) }
        pendingContent = null

        dispatch { it.onReady() }
        // 就绪后主动对齐一次 UI，避免工具栏在首次状态推送前处于空状态
        requestState()
    }

    // ==================== 内容 ====================

    /**
     * 设置内容。
     *
     * @param content ProseMirror 文档 JSON（推荐），也接受 HTML 片段。
     *   传 null 或空串会清空为单个空段落。
     *
     * 就绪前调用会排队，就绪后自动补发，不会丢内容。
     * 设置动作本身不触发 [EditorEventListener.onContentChanged]（避免回声循环），
     * 但仍会触发 [EditorEventListener.onStateChanged] 以刷新工具栏。
     */
    fun setContent(content: String?) {
        if (destroyed) {
            return
        }
        if (!isEditorReady) {
            pendingContent = content ?: ""
            return
        }
        applyContent(content ?: "")
    }

    private fun applyContent(content: String) {
        runScript("setContent(${quote(content)})")
    }

    /** 异步读取内容 JSON。就绪前回调空串。 */
    fun getContentJson(callback: (String) -> Unit) {
        if (!requireReady("getContentJson") { callback("") }) {
            return
        }
        evaluateRaw("${api()}.getJSON()") { result ->
            callback(unwrapJsString(result))
        }
    }

    /** 异步读取 HTML。就绪前回调空串。 */
    fun getHtml(callback: (String) -> Unit) {
        if (!requireReady("getHtml") { callback("") }) {
            return
        }
        evaluateRaw("${api()}.getHTML()") { result ->
            callback(unwrapJsString(result))
        }
    }

    /**
     * 主动拉取一次状态快照（正常情况下由 JS 主动推送，这里用于手动同步）。
     *
     * 会**绕过去重**强制触发 [EditorEventListener.onStateChanged]，
     * 适合在命令执行后或宿主恢复可见时对齐工具栏 UI。
     */
    fun requestState() {
        if (!requireReady("requestState")) {
            return
        }
        runScript("requestState()")
    }

    /** 主动拉取一次高度 */
    fun requestHeight() {
        if (!requireReady("requestHeight")) {
            return
        }
        runScript("getHeight()")
    }

    // ==================== 编辑属性 ====================

    /** 设置是否可编辑。就绪前调用会排队。 */
    fun setEditable(editable: Boolean) {
        if (destroyed) {
            return
        }
        if (!isEditorReady) {
            pendingEditable = editable
            return
        }
        applyEditable(editable)
    }

    private fun applyEditable(editable: Boolean) {
        runScript("setEditable(${quote(editable.toString())})")
    }

    /** 设置占位文本（空文档时显示）。就绪前调用会排队。 */
    fun setPlaceholder(placeholder: String) {
        if (destroyed) {
            return
        }
        if (!isEditorReady) {
            pendingPlaceholder = placeholder
            return
        }
        applyPlaceholder(placeholder)
    }

    private fun applyPlaceholder(placeholder: String) {
        val host = "document.getElementById('rich-editor')"
        runScript(
            "(function(){var h=$host;if(h){h.setAttribute('data-placeholder',${quote(placeholder)});}})()",
        )
    }

    fun focusEditor() {
        if (requireReady("focusEditor")) {
            runScript("focus()")
        }
    }

    fun blurEditor() {
        if (requireReady("blurEditor")) {
            runScript("blur()")
        }
    }

    // ==================== 命令 ====================

    /**
     * 执行一个命令。
     *
     * 注意：这些都是 toggle 语义，返回值只表示"命令是否被 JS 接受执行"，
     * 不代表最终的加粗/斜体状态。按钮选中态请以 [EditorEventListener.onStateChanged] 为准。
     *
     * @return 就绪且命令被接受时返回 true
     */
    fun perform(command: EditorCommand): Boolean {
        if (!requireReady("perform(${command.jsName})")) {
            return false
        }
        return runScript("exec(${quote(command.jsName)})")
    }

    /** 设置链接。value 传空串等同移除链接。 */
    fun setLink(url: String): Boolean {
        if (!requireReady("setLink")) {
            return false
        }
        if (url.isEmpty()) {
            return runScript("exec(${quote(EditorCommand.UnsetLink.jsName)})")
        }
        val payload = JSONObject().put("href", url).toString()
        return runScript("exec(${quote("setLink")}, ${quote(payload)})")
    }

    /**
     * 在光标处插入一张图片。
     *
     * @param src 图片地址，支持三种形式：
     *  - `https://...`：网络图片（注意 http 会被 mixed content 处理，见 [configureWebView]）
     *  - `data:image/...;base64,...`：直接把图片数据内嵌进文档（简单，但会让文档显著变大）
     *  - `https://appassets.androidplatform.net/user/...`：配合 [onInterceptRequest]
     *    映射到本地文件，是**大量本地图片的推荐做法**
     *
     * @return 就绪且参数合法时返回 true
     */
    fun insertImage(src: String): Boolean {
        if (!requireReady("insertImage")) {
            return false
        }
        if (src.isBlank()) {
            return false
        }
        val payload = JSONObject().put("src", src).toString()
        return runScript("exec(${quote("insertImage")}, ${quote(payload)})")
    }

    // ==================== JS 调用 ====================

    /** 用 eval 结果判断成败（返回 true/false） */
    private fun runScript(body: String): Boolean {
        if (destroyed) {
            return false
        }
        evaluateRaw("${api()}.$body") { /* 忽略结果 */ }
        return true
    }

    private fun evaluateRaw(expression: String, callback: (String) -> Unit) {
        try {
            evaluateJavascript(expression, callback)
        } catch (e: Exception) {
            bridge.reportError("执行 JS 失败: ${e.message}")
            callback("null")
        }
    }

    private fun requireReady(operation: String, onNotReady: () -> Unit = {}): Boolean {
        if (destroyed) {
            return false
        }
        if (!isEditorReady) {
            onNotReady()
            return false
        }
        return true
    }

    private fun api(): String = "window.${RichEditorContract.API_OBJECT}"

    /** 生成 JS 字符串字面量，正确处理引号/换行/Unicode */
    private fun quote(value: String): String = JSONObject.quote(value)

    /**
     * `evaluateJavascript` 返回的是 JSON 编码值：
     * JS 返回字符串时得到的是「带引号且已转义」的形式，需要反转义回来。
     */
    private fun unwrapJsString(raw: String): String {
        if (raw.isEmpty() || raw == "null") {
            return ""
        }
        return try {
            val value = JSONTokener(raw).nextValue()
            when (value) {
                is String -> value
                null -> ""
                else -> value.toString()
            }
        } catch (e: Exception) {
            raw
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 释放资源。
     *
     * WebView 是重量级对象，务必在 Activity/Fragment 销毁时调用，否则会泄漏整棵视图树。
     * 调用后本实例不可再使用。
     */
    fun release() {
        if (destroyed) {
            return
        }
        destroyed = true
        readyTimeout?.let { mainHandler.removeCallbacks(it) }
        readyTimeout = null

        if (isEditorReady) {
            // 先让 JS 侧拆掉编辑器，释放 tiptap/ProseMirror 的监听与 DOM
            evaluateRaw("${api()}.destroy()") { /* 忽略 */ }
        }
        isEditorReady = false

        removeJavascriptInterface(RichEditorContract.BRIDGE_OBJECT)
        stopLoading()
        // 置空后再 destroy，避免 WebView 在销毁过程中继续触发回调
        webViewClient = object : WebViewClient() {}
        webChromeClient = WebChromeClient()
        loadUrl("about:blank")
        destroy()
    }

    private inner class EditorWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            // 先给宿主机会（例如把自定义前缀映射到相册图片），未处理再走 assets
            onInterceptRequest?.invoke(request)?.let { return it }
            // 把 https://appassets.androidplatform.net/assets/... 映射到 assets/
            return assetLoader.shouldInterceptRequest(request.url)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            if (url.startsWith("https://${RichEditorContract.ASSET_DOMAIN}")) {
                return false
            }
            // 编辑器内的链接不应该把这个 WebView 带走
            val handler = onExternalLinkClick
            if (handler != null) {
                handler(url)
            } else {
                Log.d(TAG, "链接点击被拦截（未设置 onExternalLinkClick）: $url")
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            Log.d(TAG, "onPageFinished: $url")
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            val description = error.description?.toString().orEmpty()
            Log.e(
                TAG,
                "onReceivedError mainFrame=${request.isForMainFrame} " +
                    "url=${request.url} code=${error.errorCode} $description",
            )
            if (!request.isForMainFrame) {
                return
            }
            bridge.reportError(
                "编辑器页面加载失败: url=${request.url} code=${error.errorCode} $description",
            )
        }
    }

    private companion object {
        const val TAG = "RichEditorView"
        const val READY_TIMEOUT_MS = 8_000L
    }
}
