import { Node } from '@tiptap/core'
import { MEDIA_ALIGN_STYLE, normalizeMediaAlign, parseAlignFromStyle, sanitizeMediaUrl } from './media'

/**
 * 视频节点。
 *
 * ## 为什么原来视频会被丢掉
 * ProseMirror 只认识 schema 里声明过的节点。默认配置下只有
 * `Image` 一个媒体扩展，`<video>` **不在 schema 里**，于是解析 HTML 时
 * 整个标签被丢弃（实测 `<h3>标题</h3><p><video ...></video></p>` 会变成
 * `<h3>标题</h3><p></p>` —— 标签没了，段落变空）。所以服务端返回带视频的正文时，
 * 视频永远不显示，而且不报错。
 *
 * ## 关于 `<source>` 子标签
 * 服务端常见写法是把地址放在子标签里：
 *
 * ```html
 * <video controls="controls" width="300" height="150">
 *   <source src="https://.../a.mp4?reqtype=tsl">
 * </video>
 * ```
 *
 * 这里**两种写法都解析**（`video[src]` 与从子 `<source>` 里取），但**输出统一用
 * `<video src>` 属性**。原因：节点是 `atom`（叶子），没有 contentDOM，
 * 用属性是最简单且浏览器完全支持的形式；换成 `<source>` 需要让节点带内容子树，
 * 复杂度和出错面都会明显变大，而观感与行为完全一样。
 *
 * ## 安全
 * `src` 经 [sanitizeMediaUrl] 过滤，只允许 http/https（含相对地址），
 * 挡掉 `javascript:` 这类会被当成脚本执行的协议。
 */
export const Video = Node.create({
  name: 'video',
  group: 'block',
  // 块级 + 叶子：不对应可编辑内容，避免出现"光标进入 video 里"的怪状态
  inline: false,
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return {
      src: {
        default: null,
        parseHTML: (element) => {
          // 先看 video 自身的 src，没有再从子 <source> 取（服务端两种写法都有）
          const own = element.getAttribute('src')
          if (own && own.trim().length > 0) {
            return sanitizeMediaUrl(own)
          }
          const source = element.querySelector('source[src]')
          return sanitizeMediaUrl(source?.getAttribute('src'))
        },
      },
      poster: {
        default: null,
        parseHTML: (element) => sanitizeMediaUrl(element.getAttribute('poster')),
      },
      width: {
        default: null,
        parseHTML: (element) => element.getAttribute('width'),
      },
      height: {
        default: null,
        parseHTML: (element) => element.getAttribute('height'),
      },
      title: { default: null },
      /** 对齐方式，与段落/图片同为 `textAlign`，服务端只需认一种字段名 */
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
        tag: 'video',
        // **过滤后**没有可用地址就整个不解析。
        // 不能只判断"有没有 src 属性" —— 实测 `src="javascript:..."`
        // 会被 sanitizeMediaUrl 拦成 null，但节点仍会被创建，
        // 结果留下一个没有 src 的空播放器（看上去像一块黑矩形）。
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
      'video',
      {
        ...HTMLAttributes,
        controls: 'controls',
        // playsinline：iOS/Android 上避免一播就强制全屏接管画面
        playsinline: 'true',
        // metadata：只预加载时长/尺寸，不主动拉整个视频（正文里可能有多个视频）
        preload: 'metadata',
      },
    ]
  },
})
