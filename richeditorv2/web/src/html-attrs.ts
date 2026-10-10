/**
 * HTML 属性保留（"谨慎保管"策略）。
 *
 * ## 为什么需要这个
 * 本库的存储格式是 **HTML**（服务端→客户端、客户端→服务端都是），
 * 而 Web 端用的是 TinyMCE —— 它能产出比本库 schema 宽得多的方言。
 *
 * schema 驱动解析的特点是：**没声明过的属性会被静默丢弃**。在"存 JSON"的场景下
 * 这无所谓（JSON 是真源）；但**存 HTML 时，schema 就是最后一道关口** ——
 * 用户在移动端编辑一次、保存一次，丢掉的属性就**永久消失**，Web 端再也拿不回来。
 *
 * 典型受害者：
 *  - CMS 模板挂的类名（`<h3 class="zw-title">`）→ Web 端重新编辑时样式全丢
 *  - 缩进 `<p style="padding-left: 40px">`
 *  - 表格 `<table border="1" style="border-collapse: collapse; width: 100%">`
 *  - 代码高亮 `<pre class="language-js">`
 *
 * 所以这里采取"保管"策略：把**不认识的 class / id / style / 少量白名单属性**
 * 原样存下来再原样吐回去。代价是 JSON 里多一个 `htmlAttrs` 字段；
 * 收益是**移动端的编辑不再破坏 Web 端的排版**。
 *
 * ## 安全
 * 内容来自服务端，必须当作不可信输入。这里做两层过滤：
 *  1. **永不保留事件处理器**（`on*`）—— `<img onerror=...>` 这类是典型的注入入口
 *  2. **永不保留能执行脚本的 URL 属性**（`srcdoc`、以及值为 `javascript:` 的属性）
 * 另外 `contenteditable` 也刻意不保留：它是**编辑器状态**而不是内容，
 * 保留下来会让移动端出现"某块突然不可编辑"的怪现象。
 */

/**
 * 各类型需要**排除**的 style 属性 —— 这些由专门的扩展管理，
 * 保留下来会造成重复输出（例如 `text-align: center; text-align: center`）。
 */
export interface PreservedAttrOptions {
  /** 不保留的 style 属性名（小写） */
  skipStyle?: readonly string[]
  /** 除 class/id/style 之外，额外保留的属性名白名单（小写） */
  keepAttrs?: readonly string[]
}

/** 所有类型都不保留的 style 属性：这些是编辑器/排版的运行时产物，不是内容 */
const ALWAYS_SKIP_STYLE = ['display']

/** 永不保留的属性名 */
const FORBIDDEN_ATTRS = new Set(['contenteditable', 'srcdoc', 'spellcheck', 'draggable'])

/** 该属性值是否像"可执行"的 URL */
function isUnsafeUrlValue(value: string): boolean {
  const v = value.trim().toLowerCase().replace(/[\u0000-\u0020]/g, '')
  return v.startsWith('javascript:') || v.startsWith('vbscript:') || v.startsWith('data:text/html')
}

/** 属性名是否允许保留 */
function isSafeAttrName(name: string): boolean {
  const lower = name.toLowerCase()
  // 事件处理器一律拒绝
  if (lower.startsWith('on')) {
    return false
  }
  return !FORBIDDEN_ATTRS.has(lower)
}

/** 按需过滤 style 字符串，保留原始写法（不做归一化），只去掉被托管的属性 */
function filterStyle(raw: string | null | undefined, skip: ReadonlySet<string>): string {
  if (!raw) {
    return ''
  }
  const kept: string[] = []
  for (const decl of raw.split(';')) {
    const idx = decl.indexOf(':')
    if (idx <= 0) {
      continue
    }
    const prop = decl.slice(0, idx).trim().toLowerCase()
    const value = decl.slice(idx + 1).trim()
    if (prop.length === 0 || value.length === 0) {
      continue
    }
    if (skip.has(prop)) {
      continue
    }
    // 防御：某个 style 值里藏 url(javascript:...) 这类
    if (isUnsafeUrlValue(value)) {
      continue
    }
    kept.push(`${prop}: ${value}`)
  }
  return kept.join('; ')
}

/**
 * `htmlAttrs` 的值结构。
 *
 * 拆成三个字段而不是一个扁平 map，是为了让 JSON 可读、也便于后续单独处理某一类
 *（例如只要清理 class 时不用动 style）。
 */
export interface PreservedAttrs {
  /** 类名，原样保留（Web 端靠它套 CSS） */
  cls: string | null
  /** id，保留（锚点 `#xxx` 依赖它） */
  id: string | null
  /** 过滤后的内联样式（不含被托管属性） */
  style: string | null
  /** 额外白名单属性，如表格的 border / cellpadding */
  extra: Record<string, string> | null
}

const EMPTY_PRESERVED: PreservedAttrs = { cls: null, id: null, style: null, extra: null }

/**
 * 生成一对 `parseHTML` / `renderHTML`，用于 tiptap 的属性定义。
 *
 * 用法（在 `addGlobalAttributes` 里批量挂到多个类型上）：
 * ```ts
 * htmlAttrs: createPreservedAttributes({ skipStyle: ['text-align'] })
 * ```
 */
export function createPreservedAttributes(options: PreservedAttrOptions = {}) {
  const skip = new Set<string>([
    ...ALWAYS_SKIP_STYLE,
    ...(options.skipStyle ?? []).map((s) => s.toLowerCase()),
  ])
  const keep = new Set<string>((options.keepAttrs ?? []).map((s) => s.toLowerCase()))

  return {
    default: null as PreservedAttrs | null,

    parseHTML: (element: HTMLElement): PreservedAttrs | null => {
      const cls = element.getAttribute('class')
      const id = element.getAttribute('id')
      const style = filterStyle(element.getAttribute('style'), skip)

      let extra: Record<string, string> | null = null
      if (keep.size > 0) {
        for (const attr of Array.from(element.attributes)) {
          if (!keep.has(attr.name.toLowerCase())) {
            continue
          }
          if (!isSafeAttrName(attr.name) || isUnsafeUrlValue(attr.value)) {
            continue
          }
          extra = extra ?? {}
          extra[attr.name] = attr.value
        }
      }

      // 全空时返回 null，保持 JSON 干净（绝大多数元素都有 class，但空 span 没有）
      if (!cls && !id && !style && !extra) {
        return null
      }
      return {
        cls: cls || null,
        id: id || null,
        style: style || null,
        extra,
      }
    },

    renderHTML: (attributes: Record<string, unknown>) => {
      const value = attributes['htmlAttrs'] as PreservedAttrs | null
      if (!value) {
        return {}
      }
      return renderPreserved(value)
    },
  }
}

/**
 * 把保留的属性渲染回 HTML 属性对象。
 *
 * 注意与其它属性的 `style` 合并是安全的：tiptap 的 `mergeAttributes` 会
 * 把多处的 `style` 用 `; ` 拼起来，所以我们只管输出自己这一段。
 */
export function renderPreserved(value: PreservedAttrs | null): Record<string, unknown> {
  if (!value) {
    return {}
  }
  const out: Record<string, unknown> = {}
  if (value.cls) {
    out['class'] = value.cls
  }
  if (value.id) {
    out['id'] = value.id
  }
  if (value.style) {
    out['style'] = value.style
  }
  if (value.extra) {
    for (const [k, v] of Object.entries(value.extra)) {
      // 二次防御：即便 JSON 是外部构造的，也不要放出危险属性
      if (!isSafeAttrName(k) || isUnsafeUrlValue(String(v))) {
        continue
      }
      out[k] = v
    }
  }
  return out
}

/** 供外部构造"空值"用 */
export function emptyPreservedAttrs(): PreservedAttrs {
  return { ...EMPTY_PRESERVED }
}
