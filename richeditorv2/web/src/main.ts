import { Editor, Extension, type JSONContent } from '@tiptap/core'
import { StarterKit } from '@tiptap/starter-kit'
import { TextAlign } from '@tiptap/extension-text-align'
import { AlignedImage } from './image'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'
import { Plugin, type Transaction } from '@tiptap/pm/state'
import type { DirectEditorProps } from '@tiptap/pm/view'
import './style.css'
import { emit } from './bridge'
import { runCommand } from './commands'
import { containsEmoji, diffEmoji, listEmoji, stripEmojiFromSlice } from './emoji'
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

/**
 * 是否允许输入 emoji。默认 **false**（禁止），与 Kotlin 侧 `RichEditorView.allowEmojiInput` 保持一致。
 *
 * 默认禁止的理由：表情在不同设备/字体下渲染差异很大，而富文本内容往往还要回传后端长期保存。
 * 事前拦掉比事后清洗省事得多。需要放开时调 `setAllowEmojiInput('true')`。
 */
let allowEmojiInput = false

/**
 * 程序化写入内容期间临时置位，跳过 emoji 过滤。
 *
 * `setContent` 主要用来载入服务器下发的历史内容，那里面本来就可能有表情。
 * 若一并净化，等于**静默改写宿主的数据**——用户下次保存时才发现内容少了东西，这种问题极难排查。
 * 所以过滤只管"用户输入"，不管"程序化写入"。
 *
 * 置位是安全的：`filterTransaction` 在 dispatch 期间同步执行，用 try/finally 复位即可。
 */
let suppressEmojiFilter = false

/** 文档 → emoji 数量缓存。用 WeakMap 避免持有已废弃的文档；打字时旧文档会被反复查询 */
const emojiCountCache = new WeakMap<ProseMirrorNode, number>()

/** 取文档的 emoji 数量（带缓存，避免每次按键都重新扫一遍全文） */
function emojiCountOf(doc: ProseMirrorNode): number {
  const cached = emojiCountCache.get(doc)
  if (cached !== undefined) {
    return cached
  }
  const count = listEmoji(doc.textContent).length
  emojiCountCache.set(doc, count)
  return count
}

/**
 * 是否是撤销/重做产生的事务。
 *
 * 判据是事务 meta 里带 history 插件的标记（实测 WebView 内核中键名为 `history$`）。
 * 这里用"含 history 字样"的宽松匹配而不是硬编码键名：tiptap 将来换了实现也不会漏判，
 * 而漏判的后果是"删掉的表情撤销不回来"，属于很难排查的问题。
 */
function isHistoryTransaction(transaction: Transaction): boolean {
  // `meta` 在 Transaction 的类型定义里是 private，但运行时确实存在，也正是官方推荐的事务元数据入口
  const meta = (transaction as unknown as { meta?: Record<string, unknown> }).meta
  if (!meta) {
    return false
  }
  for (const key in meta) {
    if (key.includes('history') || key.includes('undoRedo')) {
      return true
    }
  }
  return false
}

/**
 * 回传被拦下的 emoji 时截断到这个长度。
 * 一次粘贴一整屏表情不该把超长字符串搬过 JS→Java 边界，宿主只需要知道"有哪些"。
 */
const MAX_BLOCKED_EMOJI_LENGTH = 64

/** 通知宿主"有 emoji 被拦下了"。内容为空则不发，避免无意义地喙醒 Kotlin 侧 */
function emitBlockedEmoji(blocked: string): void {
  if (blocked.length === 0) {
    return
  }
  emit(BridgeEvent.emojiBlocked, blocked.slice(0, MAX_BLOCKED_EMOJI_LENGTH))
}

/**
 * `editorProps` 的类型约束。
 *
 * 用 `Omit` 去掉 `state` / `plugins` / `dispatchTransaction`：这三个由 tiptap 自己管理，
 * `editorProps` 里不允许也不需要出现；不去掉的话 `DirectEditorProps` 会要求必填的 `state`，
 * 对象根本无法构造。
 */
type RichEditorViewProps = Omit<DirectEditorProps, 'state' | 'plugins' | 'dispatchTransaction'>

/**
 * 拦下"会让 emoji 变多"的事务。
 *
 * ## 为什么必须做成 ProseMirror 插件，而不是写进 `editorProps`
 * `filterTransaction` 在 ProseMirror 文档里是列在 `EditorProps` 下的，
 * `DirectEditorProps` 的类型声明里也有它 —— 但 **prosemirror-view 的实现从未调用过它**：
 * 实测整个 `dist` 里只有一处提及，而且是在做插件合法性校验。
 *
 * 也就是说，写进 `editorProps` 既不会有编译错误、也不会有运行时报错，
 * 只是**静默地完全不生效**（这个坑已经踩过一次，是靠端到端测试才发现的）。
 * 插件形式的 `filterTransaction` 由 `EditorState.applyTransaction` 调用，才是真正生效的路径。
 *
 * ## 为什么是"拒绝事务"而不是"事后清理"
 * 直接拒绝能覆盖所有输入路径（键盘、输入法、拖放、命令最终都走事务），
 * 而且**被拒的内容不会进入撤销栈** —— 否则会出现"按一次撤销、结果什么都没变"的幽灵步骤。
 *
 * 两个必须放行的例外：
 *  1. 撤销/重做事务 —— 用户 undo 恢复先前删掉的表情是合法操作，见 [isHistoryTransaction]；
 *  2. 程序化写入 —— 见 [suppressEmojiFilter]。
 */
const EmojiFilter = Extension.create({
  name: 'emojiFilter',

  addProseMirrorPlugins() {
    return [
      new Plugin({
        filterTransaction(transaction, state) {
          if (allowEmojiInput || suppressEmojiFilter) {
            return true
          }
          if (!transaction.docChanged || isHistoryTransaction(transaction)) {
            return true
          }
          const nextText = transaction.doc.textContent
          // 快速否定：绝大多数按键都不会引入表情，先做一次存在性判断，避开无谓的计数
          if (!containsEmoji(nextText)) {
            return true
          }
          if (listEmoji(nextText).length <= emojiCountOf(state.doc)) {
            return true
          }
          emitBlockedEmoji(diffEmoji(state.doc.textContent, nextText))
          return false
        },
      }),
    ]
  },
})

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
      //
      // 注意：**不要**把 'image' 加进来。图片的对齐由 AlignedImage 自己处理
      //（需要 display:block + margin:auto，而不是 text-align）。
      // 加进来不但无效，还会与 AlignedImage 的 textAlign 属性定义重叠。
      // 详见 src/image.ts 的注释。
      TextAlign.configure({ types: ['heading', 'paragraph'] }),
      // 图片也不在 StarterKit 里。
      // allowBase64: 允许 data URL，宿主从相册选图后可不解码直接塞进来（代价是文档变大）。
      // 相比原生 Image 多了一个 textAlign 属性（渲染成 margin:auto），使图片能居中/居右。
      AlignedImage.configure({ inline: false, allowBase64: true }),
      // emoji 输入过滤：默认禁止输入表情，详见 EmojiFilter 的注释
      EmojiFilter,
    ],
    content: initial ?? '<p></p>',
    editable: options.editable !== false,
    editorProps: {
      /**
       * 粘贴/拖放时把 emoji 从内容里摘掉，而不是整个拒绝。
       *
       * 只有在这里净化，才能既拦住表情、又保住同一次粘贴里的其他文字与格式。
       * 若不做这一步，`filterTransaction` 会把"含一个表情的一大段文字"整体拒掉，
       * 用户看到的现象是"粘贴没反应"，非常困惑。
       *
       * 注意：这个 prop 必须**在创建编辑器时**通过 `editorProps` 传入。
       * 实测用 `registerPlugin` 事后注册插件的话，插件的 `transformPasted` 不会被调用。
       */
      transformPasted: (slice) => {
        if (allowEmojiInput || suppressEmojiFilter) {
          return slice
        }
        const result = stripEmojiFromSlice(slice)
        if (result === null) {
          return slice
        }
        emitBlockedEmoji(result.removed)
        return result.slice
      },
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
    } satisfies RichEditorViewProps,
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
  // suppressEmojiFilter —— 服务器下发的历史内容即使含表情也原样保留，不做净化
  suppressEmojiFilter = true
  try {
    editor.commands.setContent(content, { emitUpdate: false })
  } finally {
    // filterTransaction 在 dispatch 期间同步执行，所以这里复位是安全的
    suppressEmojiFilter = false
  }
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
  setAllowEmojiInput(allowedJson: string): boolean
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

  /**
   * 开关 emoji 输入。
   *
   * 开关是"即时"的：关掉之后**已有**的表情不会被清掉，只是无法再新增。
   * 这样宿主可以放心地在已有含表情内容上切换策略，不会造成数据损失。
   */
  setAllowEmojiInput(allowedJson) {
    allowEmojiInput = allowedJson === 'true'
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
