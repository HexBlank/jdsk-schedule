pluginManagement {
    repositories {
        // 本机到 Google/Maven Central 的 TLS 常被干扰，优先阿里云镜像，官方源兜底。
        // 写进 settings 后即使没有全局 init 脚本也能构建（可移植）。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
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

dependencyResolutionManagement {
    // PREFER_SETTINGS：以本文件仓库为准，忽略 init 脚本注入的项目级仓库，避免冲突
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "zhusijiao-app"
include(":app")
