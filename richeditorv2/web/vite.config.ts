import { defineConfig } from 'vite'

// 产物落到 AAR 的 assets 下，由 WebViewAssetLoader 通过
// https://appassets.androidplatform.net/assets/editor/index.html 提供。
export default defineConfig({
  // 关键：index.html 与 js/css 必须用相对路径引用。
  // 若用默认的 '/'，在 appassets 域名下会解析到域名根目录而 404。
  base: './',
  build: {
    outDir: '../src/main/assets/editor',
    // outDir 在 root 之外，必须显式声明，否则 Vite 只警告不清空
    emptyOutDir: true,
    // minSdk 26 的 WebView 一般已是很新的 Chromium，但保守一点
    target: 'chrome90',
    sourcemap: false,
    cssCodeSplit: false,
    // 单入口无动态 import，下面这行保证不产生多余的 chunk
    modulePreload: false,
    rollupOptions: {
      output: {
        // 去掉 hash，方便调试与在 assets 里直接查看
        entryFileNames: 'editor.js',
        chunkFileNames: 'editor-[name].js',
        assetFileNames: 'editor.[ext]',
      },
    },
  },
})
