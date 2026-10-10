import { Image } from '@tiptap/extension-image'
import type { AlignValue } from './protocol'

/**
 * 图片节点支持对齐。
 *
 * ## 为什么需要单独处理
 * 图片被配置成 `inline: false`，即**块级叶子节点**：它是段落的**兄弟**而不是子节点
 * （`paragraph` 的 content 是 `inline*`，装不下块级节点）。因此段落的
 * `style="text-align: center"` 对图片**完全无效** —— 实测把 `text-align: center`
 * 直接加在 `<img>` 上，图片仍停在 x=0 不动。
 *
 * 更要命的是：**把 `image` 加进 `TextAlign` 的 types 是"假修复"**——
 * 那只会渲染出 `<img style="text-align: center">`，而 `text-align` 不影响
 * replaced element（如 `<img>`）自身的位置，看起来改了配置、实际还是靠左。
 * 所以这里**必须**用 `display: block` + `margin: auto` 这种真正能移动图片的写法。
 *
 * ## 为什么用内联 style 而不是 data 属性 + CSS 类
 * 段落的对齐用的是 `style="text-align: X"`（TextAlign 内置行为），本身是自包含的。
 * 图片沿用同样的思路，HTML 里直接带上 `margin` 规则，好处是**脱离本库的 CSS 也能正确渲染** ——
 * 富文本内容通常要存到服务端、在别处展示，自带样式比依赖外部 CSS 可靠。
 * 代价是 HTML 稍长，可以接受。
 *
 * ## 关于 `left`
 * 不写任何样式。图片默认就是靠左，显式写 `margin-left: 0` 没有意义，
 * 而且会让"未设置"和"显式左对齐"在文档里产生两份不同的表示。
 * 因此 `left` 一律归一化为 `null`（无属性）。
 */
export const IMAGE_ALIGN_STYLE: Readonly<Record<'center' | 'right', string>> = {
  center: 'display: block; margin-left: auto; margin-right: auto',
  right: 'display: block; margin-left: auto; margin-right: 0',
}

/**
 * 把属性值归一化成 `'center' | 'right' | null`。
 *
 * `left` 与任何非法值都归为 `null`，保证同一个视觉状态只有一种表示。
 */
export function normalizeImageAlign(value: unknown): 'center' | 'right' | null {
  return value === 'center' || value === 'right' ? value : null
}

export const AlignedImage = Image.extend({
  addAttributes() {
    return {
      // 保留 Image 自带的 src / alt / title / width / height
      ...this.parent?.(),

      /**
       * 对齐方式。属性名沿用 `textAlign`，与段落保持一致，
       * 这样服务端处理文档时同一个概念只有一种字段名。
       *
       * 注意：正因为名字相同，**绝不能再把 `image` 加进 TextAlign 的 types**，
       * 否则两处都会给 image 定义 `textAlign` 属性、渲染规则互相覆盖。
       * 图片的对齐由 [renderHTML] 自己负责。
       */
      textAlign: {
        default: null,

        /**
         * 从已保存的 HTML 里读回对齐。
         *
         * 判断依据是 `margin-left/right` 是否 `auto`（这是我们自己写出去的形式），
         * 所以解析是确定的、不会误判。
         */
        parseHTML: (element) => {
          const style = (element as HTMLElement).style
          if (style.marginLeft === 'auto' && style.marginRight === 'auto') {
            return 'center'
          }
          if (style.marginLeft === 'auto') {
            return 'right'
          }
          return null
        },

        renderHTML: (attributes) => {
          const align = normalizeImageAlign(attributes['textAlign'])
          return align ? { style: IMAGE_ALIGN_STYLE[align] } : {}
        },
      },
    }
  },
})

/** 供工具栏/命令侧复用的类型别名，说明图片对齐只可能是这两种之一或 null */
export type ImageAlign = Exclude<AlignValue, 'left'> | null
