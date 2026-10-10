package com.itc.richeditor

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
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

        /** 初始示例内容：覆盖几种典型格式，便于验证工具栏状态是否正确跟随光标 */
        const val INITIAL_CONTENT = """
            <h2 style="text-align: center">RichEditor</h2>
            <p style="text-align: right">这一段是右对齐，光标放进来时「右对齐」按钮应高亮。</p>
            <p>把光标移进下面的<strong>加粗文字</strong>或<em>斜体文字</em>，
            观察上方工具栏按钮是否自动高亮——这就是「选中态」而不是「点击态」。</p>
            <p><strong>加粗</strong>、<em>斜体</em>、<u>下划线</u>、<s>删除线</s></p>
            <p>点这个<a href="https://developer.android.com">超链接</a>试试，
            应该弹出对话框而不是直接跳走；选中它还能用更多菜单里的「移除链接」。</p>
            <p><img src="$DEMO_IMAGE" alt="demo image"></p>
            <ul><li>无序列表项</li><li>第二项</li></ul>
            <blockquote><p>引用文本</p></blockquote>
        """
    }
}