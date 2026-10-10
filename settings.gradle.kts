pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// 原先这里还有 org.gradle.toolchains.foojay-resolver-convention，
// 用于自动下载 JDK toolchain。已移除，两个原因：
//  1. 该插件的新版本要求 Gradle 8+，与降级后的 Gradle 7.4 不兼容；
//  2. daemon 用哪个 JDK 已改为在 gradle.properties 里显式指定（org.gradle.java.home），
//     不再需要 toolchain 自动下载。

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "richeditor"
include(":app")
include(":richeditorv2")
