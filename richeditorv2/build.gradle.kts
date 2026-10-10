plugins {
    alias(libs.plugins.android.library)
    // AGP 7.x 不再内置 Kotlin 支持（AGP 9 才内置），必须显式应用
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.itc.richeditorv2"
    // 刻意用较低的 compileSdk：这个值会写进 AAR 元数据（minCompileSdk），
    // 消费方项目的 compileSdk 低于它就会直接构建失败：
    //   "requires libraries and applications that depend on it to compile against version X"
    // 库本身只用 WebView / Handler / JSONObject 这类老 API，编译版本可以很低。
    // 依赖里要求最高的是 androidx.webkit:1.17.1（minCompileSdk=33），所以取 34 留一点余量。
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 必须显式声明。keep 规则放在 src/main/keepRules/ 下是新版 AGP 的目录约定，
        // AGP 7.3.1 不认识它，不会自动收集——若只靠目录约定，rules.keep 会**静默地**
        // 不被打进 AAR 的 proguard.txt。
        // 后果很难排查：消费方开启 R8 后 @JavascriptInterface 方法被改名/裁掉，
        // JS 侧所有回落到 AndroidBridge 的调用全部落空，表现为"编辑器坏了"，
        // 但没有报错、没有崩溃、没有日志。
        consumerProguardFiles("src/main/keepRules/rules.keep")
    }
    compileOptions {
        // 与目标项目对齐：Java 8（VERSION_1_8 / jvmTarget 1.8）。
        // 不改这个的话 class 字节码会是 major 55（Java 11），Java 8 的消费方读不了。
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        // 必须与 compileOptions 保持一致，否则 Gradle 会报
        // "Inconsistent JVM-target compatibility detected"
        jvmTarget = "1.8"
    }

}

dependencies {
    // 刻意**不引入任何第三方运行时依赖**。
    //
    // 本库原本依赖 androidx.webkit 提供 WebViewAssetLoader。实测（AGP 7.3.1 配套的 D8，
    // 逐个版本跑 :app:mergeExtDexDebug）：
    //   androidx.webkit 1.1.0 ~ 1.12.1  → 可正常 dex
    //   androidx.webkit 1.13.0 ~ 1.17.1 → 在 dex webkit 自身 class 时抛 NullPointerException
    // 即官方实现最多只能用到 1.12.1；且 1.16.0 还额外依赖 kotlin-stdlib 2.1.20，
    // 对 Kotlin 1.7.20 的消费方是另一种不兼容。用它还会向消费方引入
    // androidx.annotation / androidx.core 等传递依赖。
    //
    // 改为自实现 AssetResourceLoader（见源码）后，整个 AAR 变成**零依赖**：
    // 消费方不会再遇到传递依赖引起的版本冲突、dex 兼容或 kotlin-stdlib 被动升级问题。
    // 代价是约一百多行自维护代码，已由真机验证覆盖。
    testImplementation(libs.junit)
    // Android 平台自带 org.json，但 JVM 单测里是空壳，需替换为真实实现
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}