import { Node } from '@tiptap/core'
import { MEDIA_ALIGN_STYLE, normalizeMediaAlign, parseAlignFromStyle, sanitizeMediaUrl } from './media'

/**
 * 音频节点。
 *
 * TinyMCE 的 `media` 插件按扩展名判断类型：`.mp4/.webm/.ogv` → `<video>`，
 * `.mp3/.wav/.ogg` → `<audio>`。所以"TinyMCE 能插入的媒体"里包含音频，
 * 不声明这个节点的话，正文里的音频会被整段丢弃（与之前 `<video>` 被丢是同一个原因）。
 *
 * 结构与 [Video] 完全一致，只是标签名不同 —— 所以两者都保留，
 * 而不是合并成一个带 `tag` 属性的节点（那样会让 schema 更难读，也更容易在
 * parse/render 里出错）。
 */
export const Audio = Node.create({
  name: 'audio',
  group: 'block',
  inline: false,
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return {
      src: {
        default: null,
        parseHTML: (element) => {
          const own = element.getAttribute('src')
          if (own && own.trim().length > 0) {
            return sanitizeMediaUrl(own)
          }
          return sanitizeMediaUrl(element.querySelector('source[src]')?.getAttribute('src'))
        },
      },
      title: { default: null },
      textAlign: {
        default: null,
        parseHTML: (element) => parseAlignFromStyle(element),
        renderHTML: (attributes) => {
          const align = normalizeMediaAlign(attributes['textAlign'])
          return align ? { style: MEDIA_ALIGN_STYLE[align] } : {}
        },
      },
    }
  },

  parseHTML() {
    return [
      {
        tag: 'audio',
        // 与 Video 同理：必须校验**过滤后**的地址，否则
        // `src="javascript:..."` 会留下一个没有 src 的空播放器
        getAttrs: (element) => {
          const el = element as HTMLElement
          const own = sanitizeMediaUrl(el.getAttribute('src'))
          const fromSource = sanitizeMediaUrl(el.querySelector('source[src]')?.getAttribute('src'))
          return own || fromSource ? null : false
        },
      },
    ]
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'audio',
      {
        ...HTMLAttributes,
        controls: 'controls',
        preload: 'metadata',
      },
    ]
  },
})
