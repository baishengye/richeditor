import { Node } from '@tiptap/core'
import { MEDIA_ALIGN_STYLE, normalizeMediaAlign, parseAlignFromStyle, sanitizeMediaUrl } from './media'

/**
 * 内嵌页面节点（`<iframe>`）。
 *
 * 新闻/资讯类正文里很常见：第三方视频播放器、H5 互动页、地图等。
 * 和 [Video] 一样，`<iframe>` 不在 ProseMirror 的默认 schema 里，会被整段丢弃。
 *
 * ## 安全（这是最需要小心的地方）
 * 允许任意 iframe 等于把**任意第三方页面**嵌进宿主 App。这里的防线是 [sanitizeMediaUrl]：
 * 只放行 http/https（含相对地址），挡掉 `javascript:` / `data:` / `vbscript:` / `file:`。
 *
 * 但这只是**第一道防线**。iframe 内的内容本身仍不受控（可以放广告、
 * 可以做界面伪装诱导点击），所以：
 *  - 内容来源不可信时，建议宿主在服务端就把 iframe 过滤掉，或用白名单域名；
 *  - 库这里不内置域名白名单，因为"允许哪些站"是业务决策，写死在库里反而会挡住正常需求。
 *
 * 另外 `sandbox` 属性刻意**不加**：加了会破坏绝大多数播放器
 * （它们依赖脚本、同源存储等），而默认行为又因内容而异。需要收紧的话由宿主
 * 在服务端产出 HTML 时自己加，比在库里一刀切更可控。
 *
 * ## 尺寸
 * 宽高默认给 100% 宽 + 16:9 高（CSS 负责），避免播放器按固定像素把移动端布局撑破。
 * 服务端显式给的 width/height 会被保留并作为内联尺寸。
 */
export const Iframe = Node.create({
  name: 'iframe',
  group: 'block',
  inline: false,
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return {
      src: {
        default: null,
        parseHTML: (element) => sanitizeMediaUrl(element.getAttribute('src')),
      },
      width: {
        default: null,
        parseHTML: (element) => element.getAttribute('width'),
      },
      height: {
        default: null,
        parseHTML: (element) => element.getAttribute('height'),
      },
      title: {
        default: null,
        parseHTML: (element) => element.getAttribute('title'),
      },
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
        tag: 'iframe',
        // 没有 src（或 src 不安全）的 iframe 没有意义，直接不解析
        getAttrs: (element) => (sanitizeMediaUrl((element as HTMLElement).getAttribute('src')) ? null : false),
      },
    ]
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'iframe',
      {
        ...HTMLAttributes,
        frameborder: '0',
        // 无条件输出。allowfullscreen 是**布尔属性** —— 只要存在就生效，
        // 写成 `allowfullscreen="false"` 在 HTML 里依然是"允许全屏"，语义混乱又难看。
        // 所以不把它做成可配置属性，而是固定加上。
        allowfullscreen: 'true',
      },
    ]
  },
})
