// 根构建脚本：只声明插件版本，不在根工程应用。
// 版本刻意与本机 Gradle 缓存保持一致，避免重复下载：
//   AGP 8.13.0 / Kotlin 2.2.20 / Gradle 8.14.3
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}
