import { Editor, type JSONContent } from '@tiptap/core'
import { StarterKit } from '@tiptap/starter-kit'
import { TextAlign } from '@tiptap/extension-text-align'
import { Image } from '@tiptap/extension-image'
import './style.css'
import { emit } from './bridge'
import { runCommand } from './commands'
import { buildSnapshot } from './snapshot'
import { API_OBJECT, BridgeEvent, type CharacterCount, type InitOptions } from './protocol'

const MOUNT_ID = 'rich-editor'

let editor: Editor | null = null
let autoHeight = true
let lastHeight = -1
let pendingHeightFrame = 0
/** 上一次已上报的快照 JSON，用于去重 */
let lastSnapshotJson = ''
/** 上一次已上报的字数 JSON，用于去重 */
let lastCountJson = ''
/** Kotlin 可能在 onReady 之前就调用 setContent，这里先存下来，ready 后补写 */
let pendingContent: string | null = null

function rootEl(): HTMLElement | null {
  return document.getElementById(MOUNT_ID)
}

/** 接受 ProseMirror JSON 字符串或 HTML 片段 */
function normalizeContent(raw: string | null | undefined): string | JSONContent | null {
  if (!raw) {
    return null
  }
  const trimmed = raw.trim()
  if (trimmed.length === 0 || trimmed === 'null') {
    return null
  }
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return JSON.parse(trimmed) as JSONContent
    } catch (e) {
      emit(BridgeEvent.error, `初始内容 JSON 解析失败: ${String(e)}`)
      return null
    }
  }
  return trimmed
}

/** 立即测量并上报高度（带去重） */
function flushHeight(): void {
  const height = Math.ceil(document.documentElement.scrollHeight)
  // 只在变化时上报，避免高频回调拖垮 Kotlin 侧的 measure/layout
  if (height !== lastHeight) {
    lastHeight = height
    emit(BridgeEvent.height, String(height))
  }
}

/**
 * 上报高度。
 *
 * 默认走 requestAnimationFrame 合并同一帧内的多次变更；
 * 但 rAF 在**文档不可见时会被浏览器暂停**——后台标签页、尚未 attach 的 WebView、
 * 还没 show 的 Dialog 都会命中。这种情况下若只依赖 rAF，初始高度就永远不上报，
 * 宿主的自适应高度逻辑会一直拿不到值。所以这里对不可见场景改用同步测量兜底。
 */
function reportHeight(): void {
  if (!autoHeight) {
    return
  }
  if (document.hidden) {
    flushHeight()
    return
  }
  if (pendingHeightFrame !== 0) {
    return
  }
  pendingHeightFrame = requestAnimationFrame(() => {
    pendingHeightFrame = 0
    flushHeight()
  })
}

function syncEmptyClass(): void {
  const host = rootEl()
  if (!host) {
    return
  }
  const empty = editor ? editor.isEmpty : true
  host.classList.toggle('is-empty', empty)
}

function pushSnapshot(): void {
  if (!editor) {
    return
  }
  try {
    const json = JSON.stringify(buildSnapshot(editor))
    // 去重：onTransaction 每次按键/每次选区变化都会触发，无条件上报会让
    // JS→Java 的跨边界调用量放大一个量级，而绝大多数事件的快照是完全相同的。
    if (json === lastSnapshotJson) {
      return
    }
    lastSnapshotJson = json
    emit(BridgeEvent.selection, json)
  } catch (e) {
    emit(BridgeEvent.error, `快照构建失败: ${String(e)}`)
  }
}

/** 西文单词数：按空白切分，且片段里要含字母或数字（避免把纯标点算成单词） */
function countWords(text: string): number {
  const trimmed = text.trim()
  if (trimmed.length === 0) {
    return 0
  }
  return trimmed.split(/\s+/).filter((word) => /[A-Za-z0-9]/.test(word)).length
}

/**
 * 统计并上报字数。
 *
 * 单独走 [BridgeEvent.count] 而不是塞进 [EditorSnapshot]，因为快照是按内容去重的：
 * 输入文字时 bold/heading 等都不变，快照会被去重掉，字数变化就丢了。
 */
function pushCount(): void {
  if (!editor) {
    return
  }
  try {
    // doc.textContent 只含文本节点，图片等非文本节点自然不计入
    const text = editor.state.doc.textContent
    const count: CharacterCount = {
      characters: text.replace(/\s/g, '').length,
      charactersWithSpaces: text.length,
      words: countWords(text),
    }
    const json = JSON.stringify(count)
    if (json === lastCountJson) {
      return
    }
    lastCountJson = json
    emit(BridgeEvent.count, json)
  } catch (e) {
    emit(BridgeEvent.error, `字数统计失败: ${String(e)}`)
  }
}

function createEditor(options: InitOptions): boolean {
  const host = rootEl()
  if (!host) {
    emit(BridgeEvent.error, `找不到挂载点 #${MOUNT_ID}`)
    return false
  }

  autoHeight = options.autoHeight !== false
  // 重建时清空去重基准，否则新实例的首个快照可能因为与旧实例相同而被吞掉
  lastSnapshotJson = ''
  lastCountJson = ''
  if (typeof options.placeholder === 'string') {
    host.dataset['placeholder'] = options.placeholder
  }

  const initial = normalizeContent(options.content)

  editor = new Editor({
    element: host,
    extensions: [
      StarterKit.configure({
        // 关键：openOnClick 默认是 true，会让点击链接直接导航。
        // 在 WebView 里这很遭——用户想改链接文字却被带走，而且会触发宿主侧的页面导航。
        // 关掉它，改由下面的 handleDOMEvents.click 上报给宿主决定。
        link: { openOnClick: false },
      }),
      // 对齐不属于 StarterKit，必须单独引入；types 限定只允许块级节点带该属性
      TextAlign.configure({ types: ['heading', 'paragraph'] }),
      // 图片也不在 StarterKit 里。
      // allowBase64: 允许 data URL，宿主从相册选图后可不解码直接塞进来（代价是文档变大）。
      Image.configure({ inline: false, allowBase64: true }),
    ],
    content: initial ?? '<p></p>',
    editable: options.editable !== false,
    editorProps: {
      handleDOMEvents: {
        /**
         * 拦截链接点击。
         *
         * 返回 true 表示事件已处理，ProseMirror 不会再走默认行为，
         * 因此 WebView 也不会发起导航——点击只会上报给 Kotlin。
         */
        click: (_view, event) => {
          const target = event.target
          if (!(target instanceof Element)) {
            return false
          }
          const anchor = target.closest('a')
          const href = anchor?.getAttribute('href')
          if (!href) {
            return false
          }
          emit(BridgeEvent.linkClick, href)
          event.preventDefault()
          return true
        },
      },
    },
    onUpdate: ({ editor: instance }) => {
      emit(BridgeEvent.update, JSON.stringify(instance.getJSON()))
      syncEmptyClass()
      pushCount()
      reportHeight()
    },
    onSelectionUpdate: () => {
      pushSnapshot()
      reportHeight()
    },
    onTransaction: () => {
      // transaction 比 selectionUpdate 更频繁，只用来同步工具栏可用态
      pushSnapshot()
    },
    onFocus: () => emit(BridgeEvent.focus, 'true'),
    onBlur: () => emit(BridgeEvent.focus, 'false'),
    onCreate: () => {
      // 注意：这是异步触发的，Kotlin 必须等 onReady 之后再做 setContent
      syncEmptyClass()
      emit(BridgeEvent.ready, '1')
      pushSnapshot()
      pushCount()
      // 先同步测一次保证初始高度一定送达，再用 rAF 走后续的合并更新
      flushHeight()
      reportHeight()
      if (pendingContent !== null) {
        const queued = pendingContent
        pendingContent = null
        applyContent(queued)
      }
    },
    onDestroy: () => {
      emit(BridgeEvent.focus, 'false')
    },
  })

  // 内容变化时高度会变，用 ResizeObserver 覆盖"非用户输入"导致的尺寸变化
  if (autoHeight && typeof ResizeObserver === 'function') {
    const observer = new ResizeObserver(() => reportHeight())
    observer.observe(document.body)
    editor.on('destroy', () => observer.disconnect())
  }

  window.addEventListener('resize', reportHeight)
  return true
}

function applyContent(raw: string): boolean {
  if (!editor) {
    pendingContent = raw
    return false
  }
  const content = normalizeContent(raw)
  if (content === null) {
    return false
  }
  // emitUpdate:false —— 内容由 Kotlin 主动设置，不再回传 onUpdate，避免回声循环
  editor.commands.setContent(content, { emitUpdate: false })
  syncEmptyClass()
  pushSnapshot()
  // setContent 不触发 onUpdate，所以字数要手动补一次
  pushCount()
  flushHeight()
  reportHeight()
  return true
}

export interface RichEditorApi {
  init(optionsJson?: string | null): boolean
  isReady(): boolean
  setContent(content: string): boolean
  getJSON(): string
  getHTML(): string
  exec(name: string, valueJson?: string | null): boolean
  setEditable(editableJson: string): boolean
  focus(): boolean
  blur(): boolean
  getSnapshot(): string
  requestState(): boolean
  getHeight(): string
  destroy(): boolean
}

const api: RichEditorApi = {
  init(optionsJson) {
    if (editor) {
      return true
    }
    let options: InitOptions = {}
    if (optionsJson && optionsJson.trim().length > 0 && optionsJson.trim() !== 'null') {
      try {
        options = JSON.parse(optionsJson) as InitOptions
      } catch (e) {
        emit(BridgeEvent.error, `init 参数解析失败: ${String(e)}`)
        return false
      }
    }
    try {
      return createEditor(options)
    } catch (e) {
      emit(BridgeEvent.error, `编辑器初始化异常: ${String(e)}`)
      return false
    }
  },

  isReady() {
    return editor !== null
  },

  setContent(content) {
    return applyContent(content)
  },

  getJSON() {
    return editor ? JSON.stringify(editor.getJSON()) : 'null'
  },

  getHTML() {
    return editor ? editor.getHTML() : ''
  },

  exec(name, valueJson) {
    if (!editor) {
      return false
    }
    try {
      const result = runCommand(editor, name, valueJson ?? null)
      if (!result.ok && result.reason) {
        emit(BridgeEvent.error, result.reason)
      }
      syncEmptyClass()
      pushCount()
      flushHeight()
      reportHeight()
      return result.ok
    } catch (e) {
      emit(BridgeEvent.error, `命令 ${name} 执行异常: ${String(e)}`)
      return false
    }
  },

  setEditable(editableJson) {
    if (!editor) {
      return false
    }
    editor.setEditable(editableJson !== 'false')
    pushSnapshot()
    return true
  },

  focus() {
    if (!editor) {
      return false
    }
    editor.commands.focus()
    return true
  },

  blur() {
    if (!editor) {
      return false
    }
    editor.commands.blur()
    return true
  },

  getSnapshot() {
    return editor ? JSON.stringify(buildSnapshot(editor)) : 'null'
  },

  /**
   * 强制重发一次状态快照（绕过去重）。
   *
   * 用于 Kotlin 侧主动对齐 UI 的场景：例如命令执行失败、或宿主刚恢复可见、
   * 或者按钮的本地视觉态与真实编辑器状态可能不一致时，拉一次权威状态。
   */
  requestState() {
    if (!editor) {
      return false
    }
    lastSnapshotJson = ''
    pushSnapshot()
    return true
  },

  getHeight() {
    return String(Math.ceil(document.documentElement.scrollHeight))
  },

  destroy() {
    if (!editor) {
      return false
    }
    editor.destroy()
    editor = null
    lastHeight = -1
    lastSnapshotJson = ''
    lastCountJson = ''
    return true
  },
}

;(window as unknown as Record<string, unknown>)[API_OBJECT] = api

/**
 * 自举：页面脚本执行完就创建编辑器并上报 onReady。
 *
 * 不在 Kotlin 的 onPageFinished 里调 init —— 入口是 type="module"（延迟执行），
 * onPageFinished 与模块求值之间没有先后保证，从 Kotlin 主动 init 会有窗口对象
 * 尚未定义的竞态。由 JS 自己决定"何时算就绪"并告知 Kotlin，这个竞态就消失了。
 *
 * 注意：这里刻意包一层函数 + 显式判断 DOM 就绪，而不是直接写一句 `api.init()`。
 * 裸的顶层调用在打包时会被当作无副作用语句裁剪掉（实测 Vite/Rollup 会删掉），
 * 结果就是产物里根本没有自举调用、页面永远不 ready，而且构建期毫无警告。
 */
function bootstrap(): void {
  if (editor !== null) {
    return
  }
  api.init()
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bootstrap, { once: true })
} else {
  bootstrap()
}
