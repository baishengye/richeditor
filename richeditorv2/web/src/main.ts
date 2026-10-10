import { Editor, Extension, type JSONContent } from '@tiptap/core'
import { StarterKit } from '@tiptap/starter-kit'
import { TextAlign } from '@tiptap/extension-text-align'
import { TextStyleKit } from '@tiptap/extension-text-style'
import { Subscript } from '@tiptap/extension-subscript'
import { Superscript } from '@tiptap/extension-superscript'
import { TableKit } from '@tiptap/extension-table'
import { AlignedImage } from './image'
import { Audio } from './audio'
import { Iframe } from './iframe'
import { Video } from './video'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'
import { Plugin, TextSelection, type Transaction } from '@tiptap/pm/state'
import type { DirectEditorProps } from '@tiptap/pm/view'
import './style.css'
import { emit } from './bridge'
import { runCommand } from './commands'
import { containsEmoji, diffEmoji, listEmoji, stripEmoji, stripEmojiFromSlice } from './emoji'
import { buildSnapshot } from './snapshot'
import { createPreservedAttributes } from './html-attrs'
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
/**
 * 保留 HTML 属性（class / id / style / 少量白名单属性）。
 *
 * ## 为什么必须做这件事
 * 本库的存储格式是 **HTML**（服务端 ↔ 客户端都是），而 Web 端的 TinyMCE 能产出
 * 比本库 schema 宽得多的方言。schema 驱动解析会**静默丢弃**没声明过的属性 ——
 * 在存 JSON 的场景下无所谓（JSON 是真源），但**存 HTML 时 schema 就是最后一道关口**：
 * 用户在移动端编辑一次、保存一次，丢掉的属性就永久消失，Web 端再也拿不回来。
 *
 * 最典型的受害者是 **CMS 模板挂的类名**（如 `<h3 class="zw-title">`）：
 * 丢了它，Web 端重新编辑时标题样式全没。这类问题是"悄悄损坏数据"，
 * 比"显示不对"严重得多。
 *
 * 详见 src/html-attrs.ts。
 */
const PreserveHtmlAttrs = Extension.create({
  name: 'preserveHtmlAttrs',

  addGlobalAttributes() {
    /*
     * `skipStyle` 的取值不是拍脑袋定的，而是照着各扩展**实际托管**的属性列出来的。
     * 已知事实（读 node_modules 里各扩展的 dist 得到，避免猜）：
     *   - TextAlign  : types = ['heading','paragraph'] → 托管 text-align
     *   - TextStyleKit: lineHeight 的 types 被我们配置成 ['heading','paragraph'] → 托管 line-height
     *   - TextStyleKit: fontSize / color / backgroundColor / fontFamily 的 types 默认 ['textStyle']
     *   - Image      : 托管 src / alt / title / width / height
     *   - 自建 video/iframe: 托管 src / poster / width / height / title
     *   - Link       : 托管 href / target / rel / class（class 会被 mergeAttributes 去重，风险低）
     *
     * 为什么要排除：不排除就会输出两遍（`font-size: 12pt; font-size: 12pt`）。
     * 比"样式重复"更糟的是**旧值复活** —— 用户在编辑器里把字号从 12pt 改成 18pt，
     * 而 htmlAttrs 里还留着 12pt 的副本，合并顺序一旦不利就会改回去。
     */
    const alignSkip = ['text-align', 'line-height']
    const textStyleSkip = ['color', 'background-color', 'font-size', 'font-family']

    return [
      {
        // 段落/标题：text-align 与 line-height 都被托管，排除
        types: ['paragraph', 'heading'],
        attributes: {
          htmlAttrs: createPreservedAttributes({ skipStyle: alignSkip }),
        },
      },
      {
        // 其它块级容器：没有扩展托管样式，全部保留（TinyMCE 的缩进
        // `<p style="padding-left: 40px">` 落在段落上，但列表/引用同样可能带样式）
        types: ['blockquote', 'codeBlock', 'listItem', 'bulletList', 'orderedList', 'horizontalRule'],
        attributes: {
          htmlAttrs: createPreservedAttributes(),
        },
      },
      {
        // 媒体节点托管的是 margin（对齐用 margin:auto），排除掉；
        // display 已在 html-attrs.ts 里全局排除。
        //
        // 刻意**不**把 width/height 放进 keepAttrs：tiptap 的 Image 与自建的
        // video/iframe 已经托管了这两个属性，若再抄一份进 htmlAttrs，
        // 用户在编辑器内改尺寸时旧的属性值可能被"复活"。
        // 而 style 里的 width/height 没有扩展托管，继续保留（TinyMCE 惯用内联尺寸）。
        types: ['image', 'video', 'audio', 'iframe'],
        attributes: {
          htmlAttrs: createPreservedAttributes({
            skipStyle: ['margin-left', 'margin-right'],
          }),
        },
      },
      {
        // 表格：TinyMCE 会输出 border / cellpadding / cellspacing 这些
        // **属性**（不是 style），必须显式列入白名单才留得住。
        // 不保留的话，导出的表格在"没有外挂 CSS 的地方"会看不到边框。
        types: ['table', 'tableRow', 'tableCell', 'tableHeader'],
        attributes: {
          htmlAttrs: createPreservedAttributes({
            keepAttrs: ['border', 'cellpadding', 'cellspacing', 'align', 'valign', 'bgcolor'],
          }),
        },
      },
      {
        // textStyle 标记：TinyMCE 的字号/颜色就是 `<span style="...">`。
        // 颜色/字号已有专门属性托管，这里只保管"剩下的"那些 style 与 class。
        types: ['textStyle'],
        attributes: {
          htmlAttrs: createPreservedAttributes({
            skipStyle: textStyleSkip,
          }),
        },
      },
      {
        // 其它行内标记：CMS 侧可能有 `<strong class="x">`、`<span class="highlight">` 这类写法
        types: [
          'bold',
          'italic',
          'underline',
          'strike',
          'code',
          'highlight',
          'superscript',
          'subscript',
          'link',
        ],
        attributes: {
          // 链接托管着 href/target/rel，只保留 class/id/style
          htmlAttrs: createPreservedAttributes(),
        },
      },
    ]
  },
})

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

/**
 * 内容解析结果。
 *
 * 刻意把「应该清空」和「解析失败」分开：前者是正常操作，后者是宿主需要知道的错误。
 * 之前这两种情况都返回 null，导致**失败被静默吞掉** —— 调用方看到的现象就是
 * "调了 setContent、编辑器一片空白、也没有任何报错"，极难排查。
 */
type ParsedContent =
  | { kind: 'blank' }
  | { kind: 'json'; value: JSONContent }
  | { kind: 'html'; value: string }
  | { kind: 'invalid'; reason: string }

/** 接受 ProseMirror JSON 字符串或 HTML 片段 */
function parseContent(raw: string | null | undefined): ParsedContent {
  if (raw === null || raw === undefined) {
    return { kind: 'blank' }
  }
  const trimmed = raw.trim()
  if (trimmed.length === 0 || trimmed === 'null') {
    return { kind: 'blank' }
  }
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return { kind: 'json', value: JSON.parse(trimmed) as JSONContent }
    } catch (e) {
      return {
        kind: 'invalid',
        reason:
          `内容以 "{"/"[" 开头，但不是合法 JSON（${String(e)}）。` +
          '若传的其实是 HTML，请检查它是否被包在了 JSON 引号里、或含未转义的引号。',
      }
    }
  }
  return { kind: 'html', value: trimmed }
}

/**
 * 从 HTML 源码里取出所有标签名，用于诊断。
 *
 * 用正则而不是 `innerHTML` + `querySelectorAll`：后者会在**游离的 DOM 树**里
 * 真的创建出 `<img>` / `<video>` 等元素并**开始加载资源**（游离节点照样发起请求）。
 * 只是为了诊断，没必要引发网络请求。正则对"列出标签名"这个用途足够了。
 */
function extractTagNames(html: string): string[] {
  const names = new Set<string>()
  const regex = /<\s*([a-zA-Z][a-zA-Z0-9-]*)/g
  let match: RegExpExecArray | null
  while ((match = regex.exec(html)) !== null) {
    names.add(match[1].toLowerCase())
  }
  return [...names]
}

/**
 * 这些节点即使没有文字，也算「有内容」（例如只有一张图片的正文）。
 *
 * `table` 也在这里：TinyMCE 新建的表格单元格是 `&nbsp;`，trim 后没有文字，
 * 不包进来的话一张空表格会被误报成「内容已解析但为空」，反而误导排查。
 */
const MEDIA_NODE_NAMES = new Set(['image', 'video', 'audio', 'iframe', 'horizontalRule', 'table'])

/** 文档是否「实际为空」：既没有文字，也没有图片/视频/内嵌页/分割线 */
function docIsEffectivelyEmpty(doc: ProseMirrorNode): boolean {
  // 只去掉 ASCII 空白，**不能**用 trim()：
  // trim() 会把 &nbsp;(U+00A0) 也算作空白，而 TinyMCE 的空表格单元格、
  // 以及「不间断空格」按钮的产出正是 &nbsp; —— 用 trim() 会把它们
  // 误报成「内容已解析但为空」，反而干扰排查。
  if (doc.textContent.replace(/[\t\n\r ]/g, '').length > 0) {
    return false
  }
  let hasMedia = false
  doc.descendants((node) => {
    if (MEDIA_NODE_NAMES.has(node.type.name)) {
      hasMedia = true
    }
  })
  return !hasMedia
}

/**
 * 输入里有内容、但解析后文档是空的 —— 这是最容易被误判成"编辑器坏了"的情况，
 * 所以专门报出来，并把输入里出现过的标签名列出来。
 *
 * 典型成因：正文里全是编辑器不支持的标签（`<audio>` / `<embed>` / `<object>` /
 * `<canvas>` / `<svg>` 等），或者内容被更外层的不支持标签整段包住。
 */
function reportDroppedContent(source: string, isHtml: boolean): void {
  if (!editor || !docIsEffectivelyEmpty(editor.state.doc)) {
    return
  }
  const tags = isHtml ? extractTagNames(source) : []
  const tagInfo = tags.length > 0 ? `输入中出现的标签: [${tags.slice(0, 24).join(', ')}]。` : ''
  emit(
    BridgeEvent.error,
    '内容已解析，但文档里没有任何文字或媒体，因此看起来是空白的。' +
      tagInfo +
      '常见原因：内容里全是不支持的标签（如 audio / embed / object / canvas / svg），' +
      '或内容被某个不支持的标签整段包住；也可能是传入的内容本身为空。' +
      '可用 getContentJson() 确认编辑器里的实际内容。',
  )
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

  const initial = parseContent(options.content)

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
      // 视频与内嵌页。不在默认 schema 里，不加的话服务端正文里的
      // <video> / <iframe> 会被整段丢弃（不报错、只是不显示）。详见 video.ts / iframe.ts。
      Video,
      Audio,
      Iframe,
      // 上下标。TinyMCE 工具栏有这两个按钮，产出 <sup> / <sub>。
      Superscript,
      Subscript,
      // 文字样式：字号 / 颜色 / 背景色 / 行高 / 字体。
      //
      // 这几项是 TinyMCE 输出的**主力**，而且都用**内联 style** 而非 class
      //（实测 TinyMCE 7.9.3）：
      //   <span style="font-size: 12pt">字号</span>
      //   <span style="color: #ff0000">文字颜色</span>
      //   <span style="background-color: #ffff00">背景色</span>
      //   <p style="line-height: 2">行高</p>
      // 不装这个包，这些 span 会被丢掉、样式全失，但文字还在 ——
      // 即"内容对、排版不对"，很难看出是少了什么。
      //
      // lineHeight 必须显式给 types：TinyMCE 把行高写在**块级元素**上
      //（实测 `<p style="line-height: 2">`），而它的默认 types 不含 paragraph，
      // 不配的话行高会被静默丢掉。fontSize/color/backgroundColor 作用于
      // `<span>`（行内 mark），不需要额外配置。
      TextStyleKit.configure({
        lineHeight: { types: ['heading', 'paragraph'] },
      }),
      // 表格。TinyMCE 的「表格」菜单（插入/增删行列/合并单元格/表格属性）产出
      // <table style="border-collapse: collapse; width: 100%;" border="1">
      //   <colgroup><col style="width: 49.8%">...</colgroup>
      //   <tbody><tr><td>&nbsp;</td>...</tbody></table>
      // 不装则整张表格消失（连里面的文字也不剩）。
      //
      // 注意：这里只保证**能渲染、能存**；移动端不提供"插入表格"这类 UI，
      // 表格的创建与复杂编辑仍应在 Web 端完成（协作编辑场景下这也更合理）。
      TableKit.configure({ table: { resizable: false } }),
      // emoji 输入过滤：默认禁止输入表情，详见 EmojiFilter 的注释
      EmojiFilter,
      // 保留 HTML 属性（class / id / style / 白名单属性）。
      // 放在最后不影响其它扩展：它只往已有类型上**追加**一个 htmlAttrs 属性。
      // 因为存储格式是 HTML，这个扩展是"不破坏 Web 端排版"的关键，详见其注释。
      PreserveHtmlAttrs,
    ],
    content: initial.kind === 'json' || initial.kind === 'html' ? initial.value : '<p></p>',
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
      } else {
        // 初始内容直接在编辑器构造时传入，没走 applyContent，
        // 所以这里也要重置一次光标（否则一打开光标就停在文档末尾的标记里）
        resetCursorAfterSetContent()
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
  const parsed = parseContent(raw)
  if (parsed.kind === 'invalid') {
    // 解析失败：**不改动当前文档**，只报错。
    // 用空内容覆盖掉用户已有内容，比保持原样糟糕得多。
    emit(BridgeEvent.error, parsed.reason)
    return false
  }
  if (parsed.kind === 'blank') {
    // 空内容：同样不改动文档（这是刻意的，避免"数据没加载出来"把用户内容清空），
    // 但**必须报出来** —— 静默忽略是"调了没反应"这类问题的头号来源。
    // 需要明确清空请用 EditorCommand.ClearContent。
    emit(
      BridgeEvent.error,
      'setContent 收到的是空内容（空串 / null / 空白），已忽略、未改动文档。' +
        '如果需要清空编辑器，请执行 ClearContent 命令。',
    )
    return false
  }

  // emitUpdate:false —— 内容由 Kotlin 主动设置，不再回传 onUpdate，避免回声循环
  // suppressEmojiFilter —— 服务器下发的历史内容即使含表情也原样保留，不做净化
  suppressEmojiFilter = true
  try {
    editor.commands.setContent(parsed.value, { emitUpdate: false })
  } finally {
    // filterTransaction 在 dispatch 期间同步执行，所以这里复位是安全的
    suppressEmojiFilter = false
  }
  // 解析成功但结果为空 → 报出来并列出输入里的标签，便于定位
  reportDroppedContent(raw, parsed.kind === 'html')
  // 把光标从"文档末尾"收回文档开头。这一步不是锦上添花：
  // setContent 之后光标默认停在末尾，若末尾恰好落在 <em>/<strong> 等标记里，
  // 用户"打开就打字"时会变成斜体/加粗，且工具栏一打开就错误高亮。详见 resetCursorAfterSetContent。
  resetCursorAfterSetContent()
  syncEmptyClass()
  pushSnapshot()
  // setContent 不触发 onUpdate，所以字数要手动补一次
  pushCount()
  flushHeight()
  reportHeight()
  return true
}

/**
 * 载入内容后重置光标位置与「待输入格式」。
 *
 * ## 为什么必须做
 * `setContent` 之后 ProseMirror 会把选区留在**文档末尾**，这会带来两个真实问题：
 *
 * 1. **一打开就打字会带上末尾的格式**。若文档最后一段是斜体，光标就在 `<em>` 内部，
 *    用户直接输入的字会变成斜体。
 * 2. **工具栏错误高亮**。工具栏选中态是从选区推导的，所以一打开就显示「斜体」高亮，
 *    而用户什么都没选。宿主看到这个现象会以为编辑器状态错了。
 *
 * 现象 1 尤其糟：用户不会意识到自己"无意中"加上了格式，等到 Web 端看排版才发现。
 *
 * ## 做法
 * 把选区收回到文档开头，并**清空 storedMarks**。
 * storedMarks 指的是"光标处待生效的格式"（比如点了加粗按钮还没打字），
 * 载入新文档后旧的待生效格式必须清掉。
 *
 * 用 `TextSelection.near` 而不是自己算坐标：它会自动跳过图片、表格这类
 * 不能直接放光标的块，比手算可靠。
 *
 * 注意这个事务**不修改文档**，所以不会产生 undo 步骤、也不会触发 onUpdate。
 */
function resetCursorAfterSetContent(): void {
  if (!editor || editor.isDestroyed) {
    return
  }
  const { state, view } = editor
  const { doc } = state
  if (doc.childCount === 0) {
    return
  }
  // bias = 1：从文档开头向后找第一个可放光标的位置
  const selection = TextSelection.near(doc.resolve(0), 1)
  if (!selection) {
    return
  }
  const transaction = state.tr.setSelection(selection)
  transaction.setStoredMarks([])
  view.dispatch(transaction)
}

export interface RichEditorApi {
  init(optionsJson?: string | null): boolean
  isReady(): boolean
  setContent(content: string): boolean
  insertText(text: string): boolean
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

  /**
   * 在光标处插入**纯文本**。
   *
   * 用途：宿主在 PC 端有「日期/时间」「变量」这类"插入一段文本"的能力，
   * 移动端需要等价物。之前只能靠 setContent（会重置整个文档）或 exec（只认白名单命令），
   * 做不到"在光标处插一段字"，所以补上。
   *
   * **刻意不解析 HTML**：走 ProseMirror 的 `tr.insertText`，标签会被原样当字符插入。
   * 这样宿主传入用户输入的内容时不存在注入风险，语义也更明确。
   * 要插入富内容请用 setContent 或在 JS 侧扩展命令。
   *
   * ## emoji 的处理：与粘贴保持一致
   * 这里**手动摘掉** emoji，而不是把整段文本丢给 `filterTransaction` 去否决。
   * 原因：`filterTransaction` 只能整体返回 true/false，一旦文本里有一个表情，
   * 整段（包括正常的文字）都会被丢弃 —— 而且 `insertText` 还会返回 true，
   * 宿主看到的是"成功了但什么都没插进去"。这和粘贴路径（只摘表情、保留其余文字）
   * 的语义也不一致。所以这里先净化、再插入，并照常回调 onEmojiBlocked。
   */
  insertText(text) {
    if (!editor || editor.isDestroyed) {
      return false
    }
    if (typeof text !== 'string' || text.length === 0) {
      return false
    }
    let value = text
    if (!allowEmojiInput && containsEmoji(text)) {
      emitBlockedEmoji(listEmoji(text).join(''))
      value = stripEmoji(text)
      if (value.length === 0) {
        // 整段都是表情，净化后无可插入的内容
        return false
      }
    }
    try {
      const { state, view } = editor
      const { from, to } = state.selection
      view.dispatch(state.tr.insertText(value, from, to).scrollIntoView())
      // insertText 走的是底层事务，不触发 tiptap 的 onUpdate 链路，
      // 所以这里手动同步一下派生状态（空态 class / 字数 / 高度）。
      syncEmptyClass()
      pushCount()
      flushHeight()
      reportHeight()
      return true
    } catch (e) {
      emit(BridgeEvent.error, `insertText 失败: ${String(e)}`)
      return false
    }
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
