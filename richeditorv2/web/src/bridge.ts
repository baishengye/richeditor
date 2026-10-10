import { BRIDGE_OBJECT, BridgeEvent } from './protocol'

/**
 * Kotlin 侧注入的 @JavascriptInterface 对象。
 *
 * 注意：它在页面脚本执行前由 Kotlin 用 addJavascriptInterface 注入，
 * 但在桌面浏览器里调试时并不存在，所以调用前必须做能力检测。
 */
type AndroidBridgeLike = Partial<Record<(typeof BridgeEvent)[keyof typeof BridgeEvent], (payload: string) => void>>

function resolveBridge(): AndroidBridgeLike | null {
  const candidate = (window as unknown as Record<string, unknown>)[BRIDGE_OBJECT]
  if (candidate && typeof candidate === 'object') {
    return candidate as AndroidBridgeLike
  }
  return null
}

/**
 * 向 Kotlin 发送事件。
 *
 * 所有异常都在这里吞掉：桥接失败绝不能让编辑器本身崩掉，
 * 否则用户在 Android 上看到的就是一个白屏。
 */
export function emit(event: (typeof BridgeEvent)[keyof typeof BridgeEvent], payload: string): void {
  const bridge = resolveBridge()
  if (!bridge) {
    // 桌面浏览器调试场景，静默忽略
    return
  }
  try {
    const fn = bridge[event]
    if (typeof fn === 'function') {
      fn.call(bridge, payload)
    }
  } catch (e) {
    // 不能再用 emit 上报错误，否则可能无限递归
    console.warn('[RichEditor] bridge emit failed:', event, e)
  }
}

/** 桥接是否可用（Kotlin 侧据此可判断页面是否真的跑在 WebView 里） */
export function isBridgeAvailable(): boolean {
  return resolveBridge() !== null
}
