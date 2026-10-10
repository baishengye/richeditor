package com.itc.richeditor

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.itc.richeditorv2.EditorEventListener
import com.itc.richeditorv2.EditorSnapshot
import com.itc.richeditorv2.RichEditorView

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

    /** 缓存最新内容，供 onSaveInstanceState 这类同步场景使用 */
    private var latestContent: String? = null

    private val editorEvents = object : EditorEventListener {
        override fun onContentChanged(json: String) {
            latestContent = json
        }

        override fun onStateChanged(state: EditorSnapshot) {
            // 宿主也可以在这里做自己的事，例如根据 editable 调整页面其他控件
            Log.d(TAG, "state: $state")
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

        // 用 addListener 而不是直接赋值 editor.listener，
        // 这样工具栏与页面各自订阅，互不覆盖
        editor.addListener(editorEvents)

        // 点击编辑器内的链接时不在当前 WebView 里跳转，交给宿主决定
        editor.onExternalLinkClick = { url ->
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
        }

        toolbar.bindTo(editor)
        toolbar.onExpandClick = { openFullscreenEditor() }

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
     * 全屏编辑。
     *
     * 用独立的 RichEditorView 承载：打开时把主编辑器内容搬过去，关闭时搬回来。
     * 这也是 [RichEditorView.getContentJson] 异步 API 的典型用法。
     */
    private fun openFullscreenEditor() {
        val view = layoutInflater.inflate(R.layout.dialog_fullscreen_editor, null)
        val dialogEditor = view.findViewById<RichEditorView>(R.id.fullscreen_editor)
        val dialogToolbar = view.findViewById<EditorToolbar>(R.id.fullscreen_toolbar)

        dialogToolbar.bindTo(dialogEditor)
        // 全屏里不提供"全屏"按钮：未设置 onExpandClick，按钮会自动隐藏

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.setOnShowListener {
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

        /** 初始示例内容：覆盖几种典型格式，便于验证工具栏状态是否正确跟随光标 */
        const val INITIAL_CONTENT = """
            <h2 style="text-align: center">RichEditor</h2>
            <p style="text-align: right">这一段是右对齐，光标放进来时「右对齐」按钮应高亮。</p>
            <p>把光标移进下面的<strong>加粗文字</strong>或<em>斜体文字</em>，
            观察上方工具栏按钮是否自动高亮——这就是「选中态」而不是「点击态」。</p>
            <p><strong>加粗</strong>、<em>斜体</em>、<u>下划线</u>、<s>删除线</s></p>
            <ul><li>无序列表项</li><li>第二项</li></ul>
            <blockquote><p>引用文本</p></blockquote>
        """
    }
}