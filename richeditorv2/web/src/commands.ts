import type { Editor } from '@tiptap/core'
import { isSimpleCommand } from './protocol'

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
    // 插入后光标会落在图片之后，方便继续输入
    return { ok: editor.chain().focus().setImage({ src }).run() }
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
