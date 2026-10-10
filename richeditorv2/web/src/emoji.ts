import { Fragment, Slice, type Node as ProseMirrorNode } from '@tiptap/pm/model'

/**
 * emoji 识别与过滤。
 *
 * ## 为什么规则要写得这么绕
 * Unicode 里并没有一个"这就是 emoji"的单一属性，几个看起来能用的候选都有明显缺陷
 * （下面这些结论都是在真实 WebView 内核里逐字符实测出来的，不是照文档猜的）：
 *
 * - `\p{Extended_Pictographic}`：会把 `©` `®` `™` `↔` `↕` `♥` 一并算作 emoji。
 *   这些是**日常排版符号**，中文文档里 `© 2026`、`A → B`、`品牌™` 都很常见，
 *   一律当表情拦掉会让用户莫名其妙。
 * - `\p{Emoji}`：连 `0`-`9`、`#`、`*` 都算（因为它们能组成键帽表情），显然不能用。
 *
 * 最终采用的是两条规则的组合：
 *
 * 1. `\p{Emoji_Presentation}` —— **默认就以彩色 emoji 呈现**的字符。
 *    `😀 ⭐ ✨ ⚡ ✅ 🎉` 以及区域指示符（国旗）都在内；
 *    而 `© ® ™ ↔ ♥ ★` 这些默认以文本呈现的字符不在内 —— 正是想要的效果。
 * 2. `\p{Extended_Pictographic}` + `U+FE0F` —— `☺️ ❤️ ✔️` 这类
 *    **需要变体选择符才会变成 emoji** 的字符。
 *    单独写 `❤` 当普通符号用不受影响，写成彩色表情的 `❤️` 才会被拦。
 *
 * ## 必须整段匹配，不能只删基字符
 * 一个 emoji 往往由多个码位组成：
 *
 * | 形式 | 组成 |
 * |---|---|
 * | 肤色 `👍🏻` | 👍 + U+1F3FB |
 * | ZWJ 组合 `👨‍👩‍👧` | 👨 + U+200D + 👩 + U+200D + 👧 |
 * | 国旗 `🇨🇳` | 两个区域指示符 |
 * | 键帽 `1️⃣` | `1` + U+FE0F + U+20E3 |
 * | 变体 `❤️` | ❤ + U+FE0F |
 *
 * 若只删基字符，会残留 `U+FE0F` / `U+200D` 这类**不可见但占位**的字符，
 * 后续的排版、光标移动、字数统计都会出问题。所以这里用一条正则把
 * 「基字符 + 其后所有修饰」整体吃掉，一次删干净。
 */

/**
 * 是否支持 Unicode 属性转义（`\p{...}`）。
 *
 * Chrome 64+ / WebView 64+ 支持。minSdk 26 的设备如果从未更新过 WebView 可能不支持，
 * 这时退化成"按增补平面代理对识别"：能拦住绝大多数表情（它们几乎都在增补平面），
 * 但漏掉 `☺️` `❤️` 这类 BMP 字符。
 *
 * 这里刻意用 try/catch 做运行时探测，而不是依赖构建目标：正则属性是**运行时**特性，
 * 构建工具的语法降级管不到它；一旦构造失败抛在这里，整个编辑器都起不来，
 * 那就不是"少拦几个表情"的问题了。
 */
const SUPPORTS_UNICODE_PROPERTIES: boolean = (() => {
  try {
    void new RegExp('\\p{Emoji_Presentation}', 'u')
    return true
  } catch {
    return false
  }
})()

/** 匹配"一个 emoji 的起始部分" */
const BASE_SOURCE = SUPPORTS_UNICODE_PROPERTIES
  ? '(?:\\p{Emoji_Presentation}|\\p{Extended_Pictographic}\\uFE0F|[0-9#*]\\uFE0F?\\u20E3)'
  : '(?:[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]|[0-9#*]\\uFE0F?\\u20E3)'

/** 匹配"跟在基字符后面、把它补成一个完整表情的修饰部分"（可以为空） */
const TAIL_SOURCE = SUPPORTS_UNICODE_PROPERTIES
  ? '(?:\\uFE0F|\\u20E3|[\\u{1F3FB}-\\u{1F3FF}]|\\u200D\\p{Extended_Pictographic}\\uFE0F?)*'
  : '(?:\\uFE0F|\\u20E3|[\\u{1F3FB}-\\u{1F3FF}]|\\u200D[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]\\uFE0F?)*'

/**
 * 一次匹配一个完整 emoji 序列的正则源码。导出是为了让单元测试能直接校验规则边界，
 * 而不必绕经编辑器实例。
 */
export const EMOJI_SEQUENCE_SOURCE = BASE_SOURCE + TAIL_SOURCE

/** 无状态（非全局）实例，可安全复用做 test；全局实例带 lastIndex，不能共用 */
const TEST_REGEX = new RegExp(EMOJI_SEQUENCE_SOURCE, 'u')

function globalRegex(): RegExp {
  return new RegExp(EMOJI_SEQUENCE_SOURCE, 'gu')
}

/** 文本里是否含有 emoji */
export function containsEmoji(text: string): boolean {
  return text.length > 0 && TEST_REGEX.test(text)
}

/**
 * 列出文本里所有的 emoji，按出现顺序。
 *
 * 返回的是完整序列（如 `👨‍👩‍👧` 是**一个**元素而不是三个），可以直接拿去提示用户。
 */
export function listEmoji(text: string): string[] {
  const found: string[] = []
  if (text.length === 0) {
    return found
  }
  const regex = globalRegex()
  let match: RegExpExecArray | null
  while ((match = regex.exec(text)) !== null) {
    // 正常情况下不会匹配到空串；这里只是防御性处理，避免 lastIndex 不前进造成死循环
    if (match[0].length === 0) {
      regex.lastIndex++
      continue
    }
    found.push(match[0])
  }
  return found
}

/** 文本里的 emoji 个数（按完整序列计数，`👨‍👩‍👧` 算 1 个） */
export function countEmoji(text: string): number {
  return listEmoji(text).length
}

/** 去掉文本里所有 emoji */
export function stripEmoji(text: string): string {
  if (text.length === 0) {
    return text
  }
  return text.replace(globalRegex(), '')
}

/**
 * 计算"after 里比 before 多出来的 emoji"。
 *
 * 用多重集相减而不是集合差集：`😀` 从一个变成两个也算新增。
 * 用途是告诉宿主"刚才被拦下的是什么"，让它可以给出有针对性的提示。
 */
export function diffEmoji(beforeText: string, afterText: string): string {
  const pool = new Map<string, number>()
  for (const item of listEmoji(beforeText)) {
    pool.set(item, (pool.get(item) || 0) + 1)
  }
  const added: string[] = []
  for (const item of listEmoji(afterText)) {
    const remaining = pool.get(item) || 0
    if (remaining > 0) {
      pool.set(item, remaining - 1)
    } else {
      added.push(item)
    }
  }
  return added.join('')
}

/** [stripEmojiFromSlice] 的结果 */
export interface EmojiStripResult {
  /** 净化后的内容 */
  slice: Slice
  /** 被移除的 emoji（按出现顺序拼接），可用于提示用户 */
  removed: string
}

/**
 * 净化一段被粘贴/拖放的内容：删掉其中的 emoji，其余原样保留。
 *
 * 返回 `null` 表示"没有任何 emoji，无需改动"——调用方据此可以走零开销的快速路径。
 *
 * 之所以要保留结构而不是把内容拍平成纯文本：粘贴进来的可能是一整段带标题、列表、
 * 加粗的富文本，只有其中一两个表情需要去掉；把它拍平会毁掉宿主期望的格式。
 */
export function stripEmojiFromSlice(slice: Slice): EmojiStripResult | null {
  const removed: string[] = []
  const children: ProseMirrorNode[] = []
  let changed = false

  slice.content.forEach((child) => {
    const cleaned = cleanNode(child, removed)
    if (cleaned === null) {
      changed = true
      return
    }
    if (cleaned !== child) {
      changed = true
    }
    children.push(cleaned)
  })

  if (!changed) {
    return null
  }

  const fragment = Fragment.fromArray(children)
  // 整个 slice 被删空时，openStart/openEnd 会指向不存在的节点，必须归零
  const empty = fragment.childCount === 0
  return {
    slice: new Slice(fragment, empty ? 0 : slice.openStart, empty ? 0 : slice.openEnd),
    removed: removed.join(''),
  }
}

/**
 * 递归清理一个节点里的 emoji。
 *
 * @return 清理后的节点；返回 `null` 表示"该节点整体被删空"（例如只含一个表情的文本节点）
 */
function cleanNode(node: ProseMirrorNode, removed: string[]): ProseMirrorNode | null {
  if (node.isText) {
    const original = node.text || ''
    const cleaned = original.replace(globalRegex(), (match) => {
      removed.push(match)
      return ''
    })
    if (cleaned === original) {
      return node
    }
    // 文本被删空：返回 null 让上层把整个文本节点去掉，而不是留一个长度为 0 的空节点
    if (cleaned.length === 0) {
      return null
    }
    return node.type.schema.text(cleaned, node.marks)
  }

  // 图片、分割线这类叶子节点不含文本，直接放行
  if (node.isLeaf) {
    return node
  }

  const children: ProseMirrorNode[] = []
  let changed = false
  node.content.forEach((child) => {
    const cleaned = cleanNode(child, removed)
    if (cleaned === null) {
      changed = true
      return
    }
    if (cleaned !== child) {
      changed = true
    }
    children.push(cleaned)
  })

  if (!changed) {
    return node
  }
  return node.copy(Fragment.fromArray(children))
}
