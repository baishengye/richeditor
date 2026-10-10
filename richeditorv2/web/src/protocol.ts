/**
 * JS ↔ Kotlin 的通信协议定义。
 *
 * 约定（与 Kotlin 侧 `EditorBridge` / `EditorCommand` 必须保持一致）：
 *  - Kotlin → JS：调用 `window.RichEditor` 上的方法，参数与返回值都必须是 String
 *    （WebView 的 @JavascriptInterface 只支持基本类型，复杂结构一律用 JSON 字符串）。
 *  - JS → Kotlin：调用 `window.AndroidBridge` 上的方法，参数同样只能是 String。
 *
 * 之所以把常量集中在这里，是为了避免两侧"字符串漂移"——这是此类桥接最常见的故障来源。
 */

/** Kotlin 侧通过 addJavascriptInterface 注入的全局对象名 */
export const BRIDGE_OBJECT = 'AndroidBridge'

/** JS 暴露给 Kotlin 调用的全局对象名 */
export const API_OBJECT = 'RichEditor'

/** JS → Kotlin 的回调方法名 */
export const BridgeEvent = {
  /** 编辑器创建完成。注意：这是异步触发的，Kotlin 必须等它之后才能 setContent */
  ready: 'onReady',
  /** 内容变更，携带 ProseMirror JSON 字符串 */
  update: 'onUpdate',
  /** 光标/选区或标记状态变更，携带 EditorSnapshot 的 JSON */
  selection: 'onSelectionChange',
  /** 内容高度变更，携带像素高度字符串 */
  height: 'onHeightChanged',
  /** 获得/失去焦点，携带 "true"/"false" */
  focus: 'onFocusChange',
  /** 内部异常，携带错误信息 */
  error: 'onError',
} as const

/** 段落对齐方式 */
export type AlignValue = 'left' | 'center' | 'right'

/** 供 Kotlin 侧做 `enabledCommands()` 判断与工具栏状态同步的快照 */
export interface EditorSnapshot {
  /** 当前块级节点的对齐方式；未设置对齐属性时视为 left */
  align: AlignValue
  /** 当前选区是否加粗 */
  bold: boolean
  italic: boolean
  underline: boolean
  strike: boolean
  /** 行内代码 */
  code: boolean
  /** 0 表示非标题，1~6 表示对应级别 */
  heading: number
  bulletList: boolean
  orderedList: boolean
  blockquote: boolean
  codeBlock: boolean
  /** 当前链接地址，无则为 null */
  link: string | null
  canUndo: boolean
  canRedo: boolean
  /** 是否可编辑 */
  editable: boolean
  /** 当前文档是否为空（用于显示占位提示） */
  empty: boolean
}

/** `RichEditor.init()` 的入参 */
export interface InitOptions {
  /** 初始内容，ProseMirror JSON 字符串；为空则用空段落 */
  content?: string | null
  /** 是否可编辑，默认 true */
  editable?: boolean
  /** 占位文本 */
  placeholder?: string
  /** 是否自动上报高度，默认 true */
  autoHeight?: boolean
}

/**
 * 无参命令白名单。
 *
 * 采用白名单而不是 `chain[name]()` 动态派发，有两个原因：
 *  1. 避免 Kotlin 侧传入任意字符串导致意外调用到内部命令；
 *  2. 命令名集中列出，便于对照 StarterKit 实际提供的能力。
 */
export const SIMPLE_COMMANDS = [
  'toggleBold',
  'toggleItalic',
  'toggleUnderline',
  'toggleStrike',
  'toggleCode',
  'toggleBulletList',
  'toggleOrderedList',
  'toggleBlockquote',
  'toggleCodeBlock',
  'toggleHeading1',
  'toggleHeading2',
  'toggleHeading3',
  'setParagraph',
  'setHardBreak',
  'setHorizontalRule',
  'unsetLink',
  // 清除对齐（回到默认），对应工具栏「对齐」组取消选中的场景
  'unsetTextAlign',
  'undo',
  'redo',
  'selectAll',
  'clearContent',
] as const

export type SimpleCommand = (typeof SIMPLE_COMMANDS)[number]

const SIMPLE_COMMAND_SET: ReadonlySet<string> = new Set<string>(SIMPLE_COMMANDS)

export function isSimpleCommand(name: string): name is SimpleCommand {
  return SIMPLE_COMMAND_SET.has(name)
}
