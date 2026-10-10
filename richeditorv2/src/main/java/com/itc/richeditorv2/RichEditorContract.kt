package com.itc.richeditorv2

/**
 * JS 侧契约常量的 Kotlin 镜像，必须与 `web/src/protocol.ts` 保持一致。
 *
 * 之所以两边各写一份而不是共享生成：这是 AAR 的最内层，不该引入代码生成步骤；
 * 数量很少且稳定，由单元测试覆盖（见 `EditorSnapshotTest` / `EditorCommandTest`）。
 */
internal object RichEditorContract {

    /** Kotlin 注入、供 JS 回调的全局对象名 */
    const val BRIDGE_OBJECT = "AndroidBridge"

    /** JS 暴露、供 Kotlin 调用的全局对象名 */
    const val API_OBJECT = "RichEditor"

    /** WebViewAssetLoader 的默认域名 */
    const val ASSET_DOMAIN = "appassets.androidplatform.net"

    /** assets 根路径前缀 */
    const val ASSET_PREFIX = "/assets/"

    /** 编辑器入口页在 assets 中的相对路径 */
    const val INDEX_ASSET_PATH = "editor/index.html"

    /** 入口页 URL，与 [INDEX_ASSET_PATH] 对应 */
    const val INDEX_URL = "https://$ASSET_DOMAIN$ASSET_PREFIX$INDEX_ASSET_PATH"

    // ===== JS → Kotlin 的回调方法名 =====
    const val EVENT_READY = "onReady"
    const val EVENT_UPDATE = "onUpdate"
    const val EVENT_SELECTION = "onSelectionChange"
    const val EVENT_HEIGHT = "onHeightChanged"
    const val EVENT_FOCUS = "onFocusChange"
    const val EVENT_ERROR = "onError"
}
