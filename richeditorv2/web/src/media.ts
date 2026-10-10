/**
 * 图片 / 视频 / 内嵌页等「媒体块」的公共逻辑。
 *
 * 这三者的共同点：都是**块级叶子节点**，都需要
 *  1. 用 `display:block` + `margin:auto` 做对齐（`text-align` 对它们无效，见下）
 *  2. 对 URL 做安全校验（内容来自服务端，可能被注入 javascript: 等）
 */

import type { Editor } from '@tiptap/core'

/**
 * 需要单独处理对齐的媒体节点。
 *
 * 对齐命令遇到这些节点时**不能**用 `setTextAlign`（那只作用于 heading/paragraph），
 * 而要改成改节点自己的 `textAlign` 属性。
 */
export const MEDIA_ALIGN_NODES = ['image', 'video', 'iframe'] as const

/** 媒体节点名 */
export type MediaAlignNode = (typeof MEDIA_ALIGN_NODES)[number]

/** 当前选区/光标落在哪个媒体节点上；不在任何媒体节点上则返回 `null` */
export function activeMediaNode(editor: Editor): MediaAlignNode | null {
  for (const name of MEDIA_ALIGN_NODES) {
    if (editor.isActive(name)) {
      return name
    }
  }
  return null
}

/**
 * 对齐方式对应的内联样式。
 *
 * ## 为什么必须是 margin 而不是 text-align
 * 这些节点渲染成 `<img>` / `<video>` / `<iframe>`，都是 **replaced element**。
 * 实测：把 `text-align: center` 直接加在 `<img>` 上，图片**位置完全不动**（仍停在 x=0）——
 * `text-align` 只影响**行内内容**在块容器里的排布，不影响 replaced element 自身。
 *
 * 所以必须用 `display: block` + `margin-left/right: auto` 才能真正把它推向中间/右侧。
 * 这也是「把 image 加进 TextAlign 的 types」被称为"假修复"的原因。
 *
 * `left` 不在这里：不写任何样式就是靠左，显式写反而会让
 * 「未设置」和「显式左对齐」在文档里变成两种表示。
 */
export const MEDIA_ALIGN_STYLE: Readonly<Record<'center' | 'right', string>> = {
  center: 'display: block; margin-left: auto; margin-right: auto',
  right: 'display: block; margin-left: auto; margin-right: 0',
}

/** 媒体对齐值：`left` 与非法值都归一化为 `null`（不写样式） */
export function normalizeMediaAlign(value: unknown): 'center' | 'right' | null {
  return value === 'center' || value === 'right' ? value : null
}

/**
 * 从已保存的 HTML 的 style 里读回对齐。
 *
 * 判据是 `margin-left/right` 是否为 `auto` —— 这正是我们自己写出去的形式，
 * 所以解析是确定的、不会误判。注意浏览器会把 `margin-right: 0` 归一化成 `0px`，
 * 因此这里只判断 `marginLeft === 'auto'` 与 `marginRight === 'auto'`，不看具体数值。
 */
export function parseAlignFromStyle(element: Element): 'center' | 'right' | null {
  const style = (element as HTMLElement).style
  if (style.marginLeft === 'auto' && style.marginRight === 'auto') {
    return 'center'
  }
  if (style.marginLeft === 'auto') {
    return 'right'
  }
  return null
}

/** 允许的媒体地址协议 */
const SAFE_MEDIA_PROTOCOLS = new Set(['http:', 'https:'])

/**
 * 校验媒体 URL 是否安全，返回可用的地址或 `null`。
 *
 * 内容来自服务端（可能是用户投稿、也可能是被篡改的数据），
 * 直接渲染成 `<iframe src>` 等于把任意页面嵌进来。这里只放行 http/https，
 * **明确挡掉 `javascript:` / `data:` / `vbscript:` / `file:` 等**
 * （`javascript:` 尤其危险：某些 WebView 下 iframe 内的脚本能拿到宿主上下文）。
 *
 * 相对地址（不含 scheme）视为安全：它只会解析到本页面所在的 origin，
 * 也就是我们自己的 assets，出不了圈子。
 */
export function sanitizeMediaUrl(raw: unknown): string | null {
  if (typeof raw !== 'string') {
    return null
  }
  const value = raw.trim()
  if (value.length === 0) {
    return null
  }
  // 相对地址 / 协议相对地址：交给浏览器按当前 origin 解析
  if (value.startsWith('//') || !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(value)) {
    return value
  }
  try {
    const url = new URL(value)
    return SAFE_MEDIA_PROTOCOLS.has(url.protocol) ? value : null
  } catch {
    return null
  }
}
