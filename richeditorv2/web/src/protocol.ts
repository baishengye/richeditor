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
  /**
   * 点击了编辑器内的链接，携带链接地址。
   *
   * 注意：Link 扩展的 openOnClick 已被显式关闭（见 main.ts），
   * 所以点击链接**不会**导航，而是走这个回调，由宿主决定怎么处理。
   */
  linkClick: 'onLinkClick',
  /** 字数统计变化，携带 CharacterCount 的 JSON */
  count: 'onCharacterCountChanged',
  /**
   * 输入了 emoji 但被拦下，携带被拦下的 emoji 字符。
   *
   * 默认策略就是**禁止输入 emoji**（见主入口的 `setAllowEmojiInput`）。
   * 之所以专门设一个回调：输入被静默吞掉，用户分不清"编辑器不支持"和"编辑器坏了"，
   * 宿主接住它给一句提示即可。
   */
  emojiBlocked: 'onEmojiBlocked',
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
  /** 当前选区是否选中了一张图片 */
  image: boolean
  canUndo: boolean
  canRedo: boolean
  /** 是否可编辑 */
  editable: boolean
  /** 当前文档是否为空（用于显示占位提示） */
  empty: boolean
}

/**
 * 字数统计。
 *
 * 中文场景下「字数」通常指字符数，所以 [characters] 是主指标。
 * 三个值都给出来，宿主想显示哪个自己选。
 */
export interface CharacterCount {
  /** 字符数（**不包含**空白字符），中文场景下的「字数」 */
  characters: number
  /** 字符数（包含空白字符） */
  charactersWithSpaces: number
  /** 西文单词数（按空白切分且含字母/数字的片段） */
  words: number
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
  // 删除选中的图片
  'deleteSelection',
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
