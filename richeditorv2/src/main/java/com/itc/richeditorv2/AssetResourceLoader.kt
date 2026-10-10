package com.itc.richeditorv2

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.IOException

/**
 * 把 `https://appassets.androidplatform.net/assets/<path>` 映射到 APK 内的 `assets/<path>`。
 *
 * ## 为什么不用 androidx.webkit 的 WebViewAssetLoader
 * `WebViewAssetLoader` 是官方现成方案，功能上完全够用，但它的**可用版本区间被卡得很窄**。
 * 实测（AGP 7.3.1 配套的 D8，逐个版本跑 `:app:mergeExtDexDebug`）：
 *
 * | androidx.webkit | 结果 |
 * |---|---|
 * | 1.1.0 ~ 1.12.1 | 可正常 dex |
 * | 1.13.0 ~ 1.17.1 | 在 dex webkit 自身 class 时抛 NullPointerException |
 *
 * 即官方实现最多只能用到 1.12.1（时间上是 2023 年的版本）。另外 1.16.0 还会额外依赖
 * kotlin-stdlib 2.1.20，对 Kotlin 1.7.20 的消费方是另一种不兼容。
 *
 * 而本库真正用到的只有 WebViewAssetLoader 的一个能力：给 WebView 一个 **https 安全源**，
 * 规避 `file://` 下 ES module 加载受限、`localStorage` 不可用等问题。
 * 自己实现只要一百多行，换来的是**整个 AAR 不依赖任何第三方库**——
 * 对要塞进老项目的库来说这是最大的收益：没有传递依赖，就不会有版本冲突，
 * 也不会再出现 dex / Kotlin 元数据之类的连锁兼容问题，
 * 而且版本不会随时间推移被“锁死”在某个旧版三方库里。
 *
 * 若将来想换回官方实现，把版本固定在 1.12.1 即可（需重新验证 dex 与真机）。
 *
 * ## 与 WebViewAssetLoader 的行为差异
 * 刻意只实现必要部分，不做多余的事：
 *  - 只接管本库自己的域名，其他 URL 一律返回 `null` 交回 WebView 默认逻辑
 *  - 不做磁盘缓存（`shouldInterceptRequest` 的响应本来也进不了 WebView 缓存）
 *  - 不加 CORS 响应头（页面与资源同源，用不到；加了反而多一处行为差异）
 *  - 路径中的 `..`、反斜杠、绝对路径一律拒绝，避免穿越到 assets 之外
 */
internal class AssetResourceLoader(private val context: Context) {

    /**
     * 处理一次资源请求。
     *
     * @return 由本加载器提供的响应；返回 `null` 表示「这个请求不归我管」，
     *   交给 WebView 走默认流程（例如文档里的 http(s) 外链图片、data: URL）。
     */
    fun open(uri: Uri): WebResourceResponse? {
        // 只接管自己的域名。scheme 不校验 https：assetLoader 只在 https 下被调用，
        // 但保留这个 Domain 判断足以把外链排除在外。
        if (!RichEditorContract.ASSET_DOMAIN.equals(uri.host, ignoreCase = true)) {
            return null
        }
        val path = uri.path ?: return null
        if (!path.startsWith(RichEditorContract.ASSET_PREFIX)) {
            return null
        }

        // Uri.path 已经做过百分号解码，可以直接交给 AssetManager.open
        val assetPath = path.removePrefix(RichEditorContract.ASSET_PREFIX)
        if (assetPath.isEmpty() || !isSafePath(assetPath)) {
            return plainResponse(403, "Forbidden")
        }

        val stream = try {
            context.assets.open(assetPath)
        } catch (e: IOException) {
            // 资源不存在：明确返回 404，而不是交给 WebView 走默认流程。
            // 本域名并不存在于 DNS，交回默认流程只会让 WebView 再发一次注定失败的
            // 网络请求，日志里多一条无意义的错误。
            //
            // 注意：这不能消除 favicon 的 ERR_CACHE_MISS（实测仍会出现）——
            // 那条请求不走这里，是 WebView 自身的缓存探测行为，无害。
            return plainResponse(404, "Not Found")
        }

        val mime = mimeTypeOf(assetPath)
        return WebResourceResponse(mime, encodingFor(mime), 200, "OK", emptyMap(), stream)
    }

    /** 拒绝目录穿越、反斜杠与绝对路径 */
    private fun isSafePath(assetPath: String): Boolean {
        if (assetPath.startsWith("/") || assetPath.contains('\\')) {
            return false
        }
        return assetPath.split('/').none { it == ".." }
    }

    private fun plainResponse(statusCode: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", statusCode, reason, emptyMap(), null)

    /**
     * 文本类资源显式声明 utf-8。
     *
     * 不能图省事一律传 null：那样 WebView 只能靠文档自身的编码声明去猜，
     * 而这里的 js/css 里有中文（注释与提示文案），猜错就是乱码。
     * 二进制资源必须传 null，否则会被当文本处理。
     */
    private fun encodingFor(mimeType: String): String? = when {
        mimeType.startsWith("text/") -> "utf-8"
        mimeType == "application/javascript" -> "utf-8"
        mimeType == "application/json" -> "utf-8"
        mimeType == "application/manifest+json" -> "utf-8"
        else -> null
    }

    /**
     * 按扩展名给出 MIME 类型。
     *
     * 之所以手写映射而不用 `URLConnection.guessContentTypeFromName`：Android 里那张表
     * 对 `.js` / `.mjs` 覆盖不全，可能返回 null。
     *
     * **`.js` / `.mjs` 的 MIME 是最要命的一项**：页面入口是 `type="module"`，
     * 浏览器要求模块脚本必须是 JavaScript MIME 类型，否则会拒绝执行并报
     * "Expected a JavaScript module script but the server responded with a MIME type of ..."，
     * 现象是整个编辑器白屏、且没有任何 Kotlin 侧回调。所以这里必须是 text/javascript。
     */
    private fun mimeTypeOf(assetPath: String): String {
        val name = assetPath.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME_TYPES[ext] ?: "application/octet-stream"
    }

    private companion object {
        val MIME_TYPES: Map<String, String> = mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            // ES module 必须是 JavaScript MIME，见 mimeTypeOf 的注释
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "map" to "application/json",
            "webmanifest" to "application/manifest+json",
            "txt" to "text/plain",
            "xml" to "application/xml",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "avif" to "image/avif",
            "bmp" to "image/bmp",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "eot" to "application/vnd.ms-fontobject",
            "wasm" to "application/wasm",
            "mp3" to "audio/mpeg",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "pdf" to "application/pdf",
        )
    }
}
