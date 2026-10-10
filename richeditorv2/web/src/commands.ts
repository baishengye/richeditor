import type { Editor } from '@tiptap/core'
import { normalizeImageAlign } from './image'
import { isSimpleCommand } from './protocol'
import { resolveAlign } from './snapshot'

export interface CommandResult {
  ok: boolean
  /** ok=false 时的原因，会回传给 Kotlin 便于排查 */
  reason?: string
}

/** 把 toggleHeading1/2/3 这类便于 Kotlin 调用的别名映射到真实命令 */
const HEADING_ALIAS: Readonly<Record<string, 1 | 2 | 3>> = {
  toggleHeading1: 1,
  toggleHeading2: 2,
  toggleHeading3: 3,
}

/**
 * 对齐别名。
 *
 * tiptap 的 TextAlign 只有带参的 setTextAlign(alignment) / toggleTextAlign(alignment)，
 * 而 Kotlin 侧的命令枚举是无参的，所以这里提供三个无参别名，
 * 让「命令名 → 命令」的映射保持和其它按钮一致。
 */
const ALIGN_ALIAS: Readonly<Record<string, 'left' | 'center' | 'right'>> = {
  alignLeft: 'left',
  alignCenter: 'center',
  alignRight: 'right',
}

function parseHref(valueJson: string | null | undefined): string | null {
  if (!valueJson) {
    return null
  }
  try {
    const parsed: unknown = JSON.parse(valueJson)
    if (parsed && typeof parsed === 'object') {
      const href = (parsed as Record<string, unknown>)['href']
      if (typeof href === 'string' && href.trim().length > 0) {
        return href.trim()
      }
    }
  } catch {
    // 允许 Kotlin 直接传 URL 裸字符串
    const raw = valueJson.trim()
    if (raw.length > 0 && raw !== 'null') {
      return raw
    }
  }
  return null
}

/** 从 `{"src":"..."}` 或裸字符串中取出图片地址 */
function parseImageSrc(valueJson: string | null | undefined): string | null {
  if (!valueJson) {
    return null
  }
  const raw = valueJson.trim()
  if (raw.length === 0 || raw === 'null') {
    return null
  }
  if (raw.startsWith('{')) {
    try {
      const parsed = JSON.parse(raw) as Record<string, unknown>
      const src = parsed['src']
      return typeof src === 'string' && src.trim().length > 0 ? src.trim() : null
    } catch {
      return null
    }
  }
  return raw
}

/**
 * 执行一个命令。
 *
 * @param editor    tiptap 实例
 * @param name      命令名（见 protocol.ts 的 SIMPLE_COMMANDS 与下面的别名）
 * @param valueJson 需要参数的命令所携带的 JSON 字符串
 */
export function runCommand(editor: Editor, name: string, valueJson?: string | null): CommandResult {
  const headingLevel = HEADING_ALIAS[name]
  if (headingLevel !== undefined) {
    return { ok: editor.chain().focus().toggleHeading({ level: headingLevel }).run() }
  }

  const alignValue = ALIGN_ALIAS[name]
  if (alignValue !== undefined) {
    // 图片要单独处理：它是块级叶子节点，不在段落里，
    // 所以 `setTextAlign`（作用于 heading/paragraph）对它完全无效。
    // 光标落在图片上时改的是图片自身的 textAlign 属性（渲染成 margin:auto）。
    if (editor.isActive('image')) {
      return {
        ok: editor
          .chain()
          .focus()
          // left 归一化为 null：图片不写任何样式就是靠左，
          // 避免“未设置”与“显式左对齐”在文档里变成两种表示
          .updateAttributes('image', { textAlign: alignValue === 'left' ? null : alignValue })
          .run(),
      }
    }
    // 用 set 而不是 toggle：对齐是单选组，重复点同一项不应取消对齐，
    // 否则会出现「点了居中又变回左对齐」的反直觉行为。
    return { ok: editor.chain().focus().setTextAlign(alignValue).run() }
  }

  if (name === 'setLink') {
    const href = parseHref(valueJson)
    if (!href) {
      return { ok: false, reason: 'setLink 需要 {"href":"https://..."} 或裸 URL 字符串' }
    }
    return { ok: editor.chain().focus().extendMarkRange('link').setLink({ href }).run() }
  }

  if (name === 'insertImage') {
    const src = parseImageSrc(valueJson)
    if (!src) {
      return { ok: false, reason: 'insertImage 需要 {"src":"..."} 或裸地址字符串' }
    }
    // 继承当前对齐方式：用户在「居中」状态下插图，图片也应该居中。
    // 否则“选了居中但图片还是靠左”，用户会以为对齐按钮没生效。
    // 图片是独立块级节点，插入时不会自动跟随所在段落，必须显式带上。
    const textAlign = normalizeImageAlign(resolveAlign(editor))
    //
    // 不用 `setImage`：它的选项类型 `SetImageOptions` 只声明了 src/alt/title，
    // 不含我们扩展出来的 textAlign（TS 类型不会随运行时 schema 自动更新）。
    // `setImage` 内部本来就是 `insertContent({ type: 'image', attrs: options })`，
    // 所以这里直接用 insertContent，行为一致且不受类型限制。
    return {
      ok: editor.chain().focus().insertContent({ type: 'image', attrs: { src, textAlign } }).run(),
    }
  }

  if (!isSimpleCommand(name)) {
    return { ok: false, reason: `未知或未开放的命令: ${name}` }
  }

  // 已通过白名单校验，这里的动态取值是安全的
  const chain = editor.chain().focus() as unknown as Record<string, undefined | (() => { run: () => boolean })>
  const invoke = chain[name]
  if (typeof invoke !== 'function') {
    return { ok: false, reason: `当前文档/扩展不支持该命令: ${name}` }
  }
  return { ok: invoke.call(chain).run() }
}
