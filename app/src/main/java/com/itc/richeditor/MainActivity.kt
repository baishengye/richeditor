package com.itc.richeditor

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.itc.richeditorv2.CharacterCount
import com.itc.richeditorv2.EditorEventListener
import com.itc.richeditorv2.EditorSnapshot
import com.itc.richeditorv2.RichEditorView
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Demo：工具栏 + 编辑器的完整用法示例。
 *
 * 演示三个实际项目一定会遇到、且容易踩坑的点：
 *  1. **内容可以在编辑器就绪前设置** —— [RichEditorView.setContent] 会排队，
 *     就绪后自动补发，所以不必等 onCreate/onReady 再赋值。
 *  2. **按钮选中态由状态推送驱动** —— 见 [EditorToolbar]，点击时不改按钮状态。
 *  3. **保存内容用 onContentChanged 缓存，而不是在 onSaveInstanceState 里现取** ——
 *     读取内容是异步的，而 onSaveInstanceState 是同步调用，现取必然拿不到。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var editor: RichEditorView
    private lateinit var toolbar: EditorToolbar
    private lateinit var countBar: TextView
    private lateinit var emojiSwitch: MaterialSwitch

    /** 缓存最新内容，供 onSaveInstanceState 这类同步场景使用 */
    private var latestContent: String? = null

    /**
     * 相册选图。
     *
     * 用 PickVisualMedia 而不是 ACTION_GET_CONTENT：Android 13+ 走系统 photo picker，
     * **不需要任何存储权限**，低版本自动回退到系统选择器，免去动态权限那一套。
     */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        Toast.makeText(this, R.string.image_loading, Toast.LENGTH_SHORT).show()
        // 解码 + 压缩 + base64 都是耗时操作，必须离开主线程，否则大图会直接 ANR
        thread(name = "image-encode") {
            val dataUrl = readImageAsDataUrl(uri)
            runOnUiThread {
                if (dataUrl != null) {
                    editor.insertImage(dataUrl)
                } else {
                    Toast.makeText(this, R.string.image_pick_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val editorEvents = object : EditorEventListener {
        override fun onContentChanged(json: String) {
            latestContent = json
        }

        override fun onStateChanged(state: EditorSnapshot) {
            // 宿主也可以在这里做自己的事，例如根据 editable 调整页面其他控件
            Log.d(TAG, "state: $state")
        }

        /**
         * 点击了编辑器内的链接。
         *
         * 库已经拦下了 WebView 的导航（Link 扩展的 openOnClick 已关闭），
         * 所以这里必须自己处理，否则用户会感觉“链接点了没反应”。
         */
        override fun onLinkClick(url: String) {
            Log.d(TAG, "link click: $url")
            showLinkDialog(url)
        }

        override fun onCharacterCountChanged(count: CharacterCount) {
            countBar.text = if (count.charactersWithSpaces > count.characters) {
                getString(R.string.count_bar_with_spaces, count.characters, count.charactersWithSpaces)
            } else {
                getString(R.string.count_bar, count.characters)
            }
        }

        override fun onEmojiBlocked(blocked: String) {
            // 库把输入拦下了，这里必须给一点反馈。
            // 否则用户按了输入法的表情面板却毫无反应，会以为编辑器坏了。
            Toast.makeText(this@MainActivity, getString(R.string.emoji_blocked, blocked), Toast.LENGTH_SHORT)
                .show()
        }

        override fun onFocusChanged(focused: Boolean) {
            Log.d(TAG, "focus: $focused")
        }

        override fun onError(message: String) {
            // 库会把 JS 侧异常、资源加载失败等通过这里抛出，
            // 务必接住，否则线上出问题时是无声无息的
            Log.e(TAG, "editor error: $message")
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 让页面避开状态栏/导航栏
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        editor = findViewById(R.id.editor)
        toolbar = findViewById(R.id.editor_toolbar)
        countBar = findViewById(R.id.count_bar)
        emojiSwitch = findViewById(R.id.emoji_switch)

        // 长按字数栏看导出 HTML，用于在真机上核对属性保留情况
        countBar.setOnLongClickListener {
            showExportedHtml()
            true
        }

        // 用 addListener 而不是直接赋值 editor.listener，
        // 这样工具栏与页面各自订阅，互不覆盖
        editor.addListener(editorEvents)

        // 点击编辑器内的链接时不在当前 WebView 里跳转，交给宿主决定
        editor.onExternalLinkClick = { url ->
            showLinkDialog(url)
        }

        toolbar.bindTo(editor)
        // emoji 输入开关：库默认就禁止输入表情，这里只是演示如何切换。
        // 注意开关状态以库为准（editor.isEmojiInputAllowed），不要反过来假设按钮是唯一来源。
        emojiSwitch.isChecked = editor.isEmojiInputAllowed
        emojiSwitch.setOnCheckedChangeListener { _, checked ->
            editor.setAllowEmojiInput(checked)
        }
        toolbar.onExpandClick = { openFullscreenEditor() }
        // 选图逻辑（相册 / 拍照 / 网络）由宿主决定，库只负责把图片插进文档
        toolbar.onInsertImage = {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }

        // 恢复内容：就绪前调用会被排队，无需等待 onReady
        val restored = savedInstanceState?.getString(KEY_CONTENT)
        val initial = restored ?: INITIAL_CONTENT
        editor.setContent(initial)
        latestContent = initial

        // 加载编辑器页面。放在最后调用，确保上面的 listener / 初始内容都已就位
        editor.loadEditor()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // latestContent 由 onContentChanged 持续维护，这里是同步可用的
        latestContent?.let { outState.putString(KEY_CONTENT, it) }
    }

    override fun onDestroy() {
        // WebView 是重量级对象，必须释放，否则泄漏整棵视图树
        editor.release()
        super.onDestroy()
    }

    /**
     * 链接点击后的处理。
     *
     * 用一个对话框而不是直接跳转：编辑器里误触链接太容易了，
     * “点一下就离开 App”体验很差，给用户一个明确选择更稳。
     */
    private fun showLinkDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_link)
            .setMessage(url)
            .setPositiveButton(R.string.link_action_open) { _, _ -> openUrl(url) }
            .setNeutralButton(R.string.link_action_copy) { _, _ -> copyUrl(url) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * 长按字数栏 → 弹出当前文档导出的 HTML。
     *
     * 存在的意义是**让"属性有没有被保住"这件事可以在手机上直接看见**。
     * 服务端和 Web 端之间传的是 HTML，所以「编辑一次、保存一次之后 HTML 变没变」
     * 是最关键的验证点，而它光看渲染结果看不出来 —— 必须看源码。
     *
     * 顺带演示 [RichEditorView.getHtml]：它和 [RichEditorView.getContentJson] 一样是
     * **异步回调**（要跨 WebView 取一次值），不能当同步方法用。
     */
    private fun showExportedHtml() {
        Toast.makeText(this, R.string.export_html_loading, Toast.LENGTH_SHORT).show()
        // evaluateJavascript 的回调本来就在主线程，这里不额外切线程
        editor.getHtml { html ->
            if (html.isEmpty()) {
                Toast.makeText(this, R.string.export_html_empty, Toast.LENGTH_SHORT).show()
                return@getHtml
            }
            val content = TextView(this).apply {
                text = html
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            val scroll = ScrollView(this).apply {
                addView(content)
                // 必须自己给高度上限：AlertDialog 的内容区高度不限，
                // 而 HTML 动辄几千字符，不限制会把对话框撑到超出屏幕、按钮都点不到
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.6f).toInt(),
                )
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.export_html_title)
                .setView(scroll)
                .setPositiveButton(R.string.link_action_copy) { _, _ -> copyHtml(html) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun copyHtml(html: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("html", html))
        Toast.makeText(this, R.string.export_html_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 只在白名单 scheme 下才交给系统打开。
     *
     * 链接文本来自文档内容，可以是任意字符串（包括 `javascript:` / `intent:` 这类）。
     * 不加校验就对它执行 `ACTION_VIEW` 是有风险的，尤其当文档可能由外部导入时。
     */
    private fun openUrl(url: String) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_LINK_SCHEMES) {
            val shown = scheme ?: url
            Toast.makeText(this, getString(R.string.link_unsupported, shown), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 把相册图片转成 data URL。
     *
     * 分两步解码避免 OOM：先只读尺寸（`inJustDecodeBounds`）算出采样率，再按采样率解码。
     * 直接 decode 一张几千万像素的照片，在低端机上会直接崩。
     *
     * 注意这是**最简做法**：base64 会让文档明显变大（约为原图的 4/3，且随文档一起存储）。
     * 图片多或体积大时，更合适的是把文件拷进私有目录，配合
     * [RichEditorView.onInterceptRequest] 用 https 地址引用，这里不展开。
     */
    private fun readImageAsDataUrl(uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            var sample = 1
            while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            bitmap.recycle()

            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "读取图片失败", e)
            null
        }
    }

    /**
     * 全屏编辑。
     *
     * 用独立的 RichEditorView 承载：打开时把主编辑器内容搬过去，关闭时搬回来。
     * 这也是 [RichEditorView.getContentJson] 异步 API 的典型用法。
     */
    private fun openFullscreenEditor() {
        val view = layoutInflater.inflate(R.layout.dialog_fullscreen_editor, null)
        val dialogEditor = view.findViewById<RichEditorView>(R.id.fullscreen_editor)
        val dialogToolbar = view.findViewById<EditorToolbar>(R.id.fullscreen_toolbar)
        val dialogCountBar = view.findViewById<TextView>(R.id.fullscreen_count_bar)

        // 全屏编辑器要用自己的监听器：复用时字数会串到主界面的状态栏上
        dialogEditor.addListener(object : EditorEventListener {
            override fun onCharacterCountChanged(count: CharacterCount) {
                dialogCountBar.text = getString(R.string.count_bar, count.characters)
            }

            override fun onLinkClick(url: String) {
                showLinkDialog(url)
            }

            override fun onEmojiBlocked(blocked: String) {
                Toast.makeText(this@MainActivity, getString(R.string.emoji_blocked, blocked), Toast.LENGTH_SHORT)
                    .show()
            }
        })

        dialogToolbar.bindTo(dialogEditor)
        // 全屏里不提供"全屏"按钮：未设置 onExpandClick，按钮会自动隐藏

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.setOnShowListener {
            // 新建的 RichEditorView 是独立实例，不会继承主编辑器的设置，必须同步一次
            dialogEditor.setAllowEmojiInput(editor.isEmojiInputAllowed)
            dialogEditor.loadEditor()
            editor.getContentJson { json ->
                if (json.isNotEmpty()) {
                    dialogEditor.setContent(json)
                }
            }
        }

        dialog.setOnDismissListener {
            dialogEditor.getContentJson { json ->
                if (json.isNotEmpty()) {
                    editor.setContent(json)
                    latestContent = json
                }
            }
            dialogEditor.release()
        }

        dialog.show()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val KEY_CONTENT = "editor_content"

        /** 允许交给系统打开的 scheme 白名单，其余一律拒绝 */
        val ALLOWED_LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

        /** 图片最大边长（像素）。超过则按 2 的幂采样缩小，控制 base64 体积 */
        const val MAX_IMAGE_EDGE = 1280

        /** JPEG 压缩质量，兼顾观感与体积 */
        const val JPEG_QUALITY = 80

        /** 内联 SVG 的 data URL，用来验证图片显示；换成任意 https 地址或 base64 都可以 */
        const val DEMO_IMAGE =
            "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmci" +
                "IHdpZHRoPSIxODAiIGhlaWdodD0iODAiPjxyZWN0IHdpZHRoPSIxODAiIGhlaWdodD0iODAi" +
                "IHJ4PSI4IiBmaWxsPSIjNDI4NUY0Ii8+PHRleHQgeD0iOTAiIHk9IjQ4IiBmb250LWZhbWls" +
                "eT0ic2Fucy1zZXJpZiIgZm9udC1zaXplPSIyMCIgZmlsbD0iI2ZmZmZmZiIgdGV4dC1hbmNo" +
                "b3I9Im1pZGRsZSI+SU1BR0UgT0s8L3RleHQ+PC9zdmc+"

        /*
         * 演示用的公开测试媒体。
         *
         * ## 挑选标准（下面这些链接都是在真机上实测过 readyState=4 才写进来的）
         *  1. **必须 https**。编辑器页面自身就是
         *     https://appassets.androidplatform.net/...，WebView 在 https 页面里
         *     加载 http 资源会按 mixed content 拦掉。
         *  2. **体积要小**，移动网络下点开就能播。
         *  3. 优先选支持 `Accept-Ranges: bytes` 的（视频拖动进度条要靠它）。
         *
         * ## 两个容易踩的坑
         *  - **宿主必须声明 INTERNET 权限**，否则现象是"播放器出来了但一直转圈"，
         *    很容易误判成库的问题。演示工程已在 AndroidManifest.xml 里声明。
         *  - **logcat 里 `onReceivedError ... mainFrame=false` 常常是假警报**。
         *    实测正文里 5 个媒体元素全部 readyState=4（真的加载好了），
         *    其中两个视频仍然报了这个错。判断媒体是否可用要查 DOM 的
         *    readyState / error，不要看这个回调。详见 RichEditorView.onReceivedError。
         */

        /** 图片（https 外链），用来验证网络与权限链路 */
        const val DEMO_IMAGE_EXTERNAL =
            "https://interactive-examples.mdn.mozilla.net/media/cc0-images/grapefruit-slice-332-332.jpg"

        /** 视频：`<video src>` 写法。w3schools 的 Big Buck Bunny 片段，788 KB，支持 Range */
        const val DEMO_VIDEO_SRC = "https://www.w3schools.com/html/mov_bbb.mp4"

        /** 视频：`<video><source></video>` 写法。1920×1080，2.8 MB */
        const val DEMO_VIDEO_SOURCE = "https://samplelib.com/lib/preview/mp4/sample-5s.mp4"

        /** 视频封面。poster 是 `<video>` 的属性，本库会保留（也可用来验证属性透传） */
        const val DEMO_VIDEO_POSTER = "https://media.w3.org/2010/05/sintel/poster.png"

        /** 音频：`<audio src>` 写法。约 30 KB 的 mp3 */
        const val DEMO_AUDIO_SRC = "https://www.w3schools.com/html/horse.mp3"

        /** 音频：`<audio><source></audio>` 写法。52 KB 的 mp3 */
        const val DEMO_AUDIO_SOURCE = "https://samplelib.com/lib/preview/mp3/sample-3s.mp3"

        /** 初始示例内容：覆盖几种典型格式，便于验证工具栏状态是否正确跟随光标 */
        val INITIAL_CONTENT = """
            <h2 class="zw-title" style="text-align: center">富文本编辑器</h2>
            <p style="text-align: right">这一段是右对齐，光标放进来时「右对齐」按钮应高亮。</p>
            <p>把光标移进下面的<strong>加粗文字</strong>或<em>斜体文字</em>，观察上方工具栏按钮是否自动高亮——这就是「选中态」而不是「点击态」。</p>
            <p><strong>加粗</strong>、<em>斜体</em>、<u>下划线</u>、<s>删除线</s>、<code>行内代码</code>、x<sup>2</sup>、H<sub>2</sub>O</p>

            <h3>1. PC 端（TinyMCE）产的样式，现在都能保住</h3>
            <p style="line-height: 2">这一段的 line-height 是 2，来自 TinyMCE 的行高设置。</p>
            <p><span style="font-size: 18pt">18pt 字号</span>、<span style="color: #d32f2f">红色文字</span>、<span style="background-color: #fff59d">黄色背景</span></p>
            <p style="padding-left: 40px">这一段有 40px 左缩进（TinyMCE 的「增加缩进」）。</p>
            <p class="note" id="note-1">这一段带 class="note" 和 id，属于 CMS 模板挂的类名。以前编辑一次就会被抹掉，现在会原样保留。</p>

            <h3>2. 表格</h3>
            <table border="1" cellpadding="4" style="border-collapse: collapse; width: 100%">
              <tbody>
                <tr><td><strong>能力</strong></td><td><strong>状态</strong></td></tr>
                <tr><td>表格 border / width</td><td>保留</td></tr>
                <tr><td>class / id / style</td><td>保留</td></tr>
                <tr><td>分页符</td><td>暂不支持</td></tr>
              </tbody>
            </table>

            <h3>3. 代码块</h3>
            <pre class="language-kotlin"><code>editor.setContent(serverHtml)   // 服务端下发什么就传什么
editor.insertText("2026-10-10") // 在光标处插一段纯文本，不解析 HTML</code></pre>

            <h3>4. 列表与引用</h3>
            <ul><li>无序列表项</li><li>第二项</li></ul>
            <ol><li>有序列表第一项</li><li>第二项</li></ol>
            <blockquote><p>引用文本</p></blockquote>
            <hr>

            <h3>5. 图片</h3>
            <p><img src="$DEMO_IMAGE" alt="demo image"></p>
            <p>上面这张是内嵌的 data URL，从相册选图也是这条路径。下面这张是外链 https 图片 ——
            要用外链，宿主必须声明 INTERNET 权限（演示工程已声明）。</p>
            <p><img src="$DEMO_IMAGE_EXTERNAL" alt="external"></p>
            <p>选中图片后再点「居中」，图片会真的居中（靠 margin:auto，不是 text-align）。</p>

            <h3>6. 视频</h3>
            <p>第一种写法：<code>&lt;video src="..."&gt;</code></p>
            <p><video controls src="$DEMO_VIDEO_SRC"></video></p>
            <p>第二种写法：<code>&lt;video&gt;&lt;source src="..."&gt;&lt;/video&gt;</code>，
            并带 poster 封面。服务端两种写法都很常见，本库两种都认。</p>
            <p><video controls poster="$DEMO_VIDEO_POSTER"><source src="$DEMO_VIDEO_SOURCE" type="video/mp4"></video></p>
            <p>视频不会自动播放（WebView 的 <code>mediaPlaybackRequiresUserGesture</code> 默认为 true，
            本库也未放开），点播放键即可。全屏按钮**无效** —— 它需要 WebChromeClient.onShowCustomView，
            本库没有实现。</p>

            <h3>7. 音频</h3>
            <p><code>&lt;audio src="..."&gt;</code></p>
            <p><audio controls src="$DEMO_AUDIO_SRC"></audio></p>
            <p><code>&lt;audio&gt;&lt;source src="..."&gt;&lt;/audio&gt;</code></p>
            <p><audio controls><source src="$DEMO_AUDIO_SOURCE" type="audio/mpeg"></audio></p>
            <p>音频播放器由 WebView 自己提供，外观随系统版本变化，不是本库画的。</p>

            <h3>8. 链接</h3>
            <p>点这个<a href="https://developer.android.com">超链接</a>试试，应该弹出对话框而不是直接跳走。</p>

            <p><em>提示：长按右下角的字数，可以查看当前文档导出的 HTML —— 用它可以验证上面的属性有没有被保住。</em></p>
        """.trimIndent()
    }
}