import type { Editor } from '@tiptap/core'
import type { AlignValue, EditorSnapshot } from './protocol'

/** can() 在某些扩展缺失时可能抛错，这里统一兜底，避免影响整条快照构建 */
function safeCan(editor: Editor, command: 'undo' | 'redo'): boolean {
  try {
    return editor.can()[command]()
  } catch {
    return false
  }
}

/**
 * 解析当前块级节点的对齐方式。
 *
 * TextAlign 的 defaultAlignment 为 null，未设置对齐的段落不带 textAlign 属性，
 * 所以这里把「没有该属性」归一化为 left —— 这样工具栏的左对齐按钮在默认情况下
 * 就是选中态，符合「单选组总有一项被选中」的预期。
 *
 * 图片要单独分支：它是块级叶子节点，**不在段落里**（是段落的兄弟），
 * 所以段落的 textAlign 对它无效，图片用的是自己 attrs 上的 textAlign
 * （渲染成 margin:auto，见 src/image.ts）。而 `isActive({ textAlign })`
 * 只会在块级容器节点上查找，光标落在图片上时永远返回 false ——
 * 不分支的话，选中图片时工具栏会一直显示「左对齐」，与实际不符。
 */
export function resolveAlign(editor: Editor): AlignValue {
  if (editor.isActive('image')) {
    const value = editor.getAttributes('image')['textAlign']
    return value === 'center' || value === 'right' ? value : 'left'
  }
  if (editor.isActive({ textAlign: 'center' })) {
    return 'center'
  }
  if (editor.isActive({ textAlign: 'right' })) {
    return 'right'
  }
  return 'left'
}

/** 采集当前编辑器状态，供 Kotlin 侧同步工具栏 */
export function buildSnapshot(editor: Editor): EditorSnapshot {
  const headingLevel = editor.isActive('heading') ? editor.getAttributes('heading')['level'] : 0
  const linkAttrs = editor.getAttributes('link')
  const href = linkAttrs['href']

  return {
    align: resolveAlign(editor),
    bold: editor.isActive('bold'),
    italic: editor.isActive('italic'),
    underline: editor.isActive('underline'),
    strike: editor.isActive('strike'),
    code: editor.isActive('code'),
    heading: typeof headingLevel === 'number' ? headingLevel : 0,
    bulletList: editor.isActive('bulletList'),
    orderedList: editor.isActive('orderedList'),
    blockquote: editor.isActive('blockquote'),
    codeBlock: editor.isActive('codeBlock'),
    link: typeof href === 'string' && href.length > 0 ? href : null,
    image: editor.isActive('image'),
    canUndo: safeCan(editor, 'undo'),
    canRedo: safeCan(editor, 'redo'),
    editable: editor.isEditable,
    empty: editor.isEmpty,
  }
}
