// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 7.x 不再内置 Kotlin 支持（AGP 9 才内置），必须显式应用 Kotlin 插件
    alias(libs.plugins.kotlin.android) apply false
}