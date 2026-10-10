plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.itc.richeditorv2"
    // 刻意用较低的 compileSdk：这个值会写进 AAR 元数据（minCompileSdk），
    // 消费方项目的 compileSdk 低于它就会直接构建失败：
    //   "requires libraries and applications that depend on it to compile against version X"
    // 库本身只用 WebView / Handler / JSONObject 这类老 API，编译版本可以很低。
    // 依赖里要求最高的是 androidx.webkit:1.17.1（minCompileSdk=33），所以取 34 留一点余量。
    compileSdk {
        version = release(34)
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    // 库内唯一的运行时依赖：WebViewAssetLoader，给 WebView 一个 https 安全源，
    // 避免 file:// 下 ESM / 存储受限。
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    // Android 平台自带 org.json，但 JVM 单测里是空壳，需替换为真实实现
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}