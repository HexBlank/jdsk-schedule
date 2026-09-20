import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名：存在 keystore.properties 时启用（该文件与 *.jks 已被 gitignore）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()
val apiBaseUrl = providers.gradleProperty("apiBaseUrl").orElse("").get().trimEnd('/')
val devAuthOpenId = providers.gradleProperty("devAuthOpenId").orElse("").get()

android {
    namespace = "com.zhusijiao.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhusijiao.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 22
        versionName = "1.2.19"
        resourceConfigurations += listOf("zh", "en")
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEV_AUTH_OPENID", "\"${devAuthOpenId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 代码压缩 + 资源压缩：无原生库的纯 Kotlin 应用，release 包可压到很小
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 存在 keystore.properties 时用 release 签名，产出可直接安装的 app-release.apk；否则产出未签名包
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }
}

// 正式包必须显式注入 HTTPS API，且绝不允许携带开发身份。
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        require(apiBaseUrl.startsWith("https://")) {
            "Release 构建必须提供 -PapiBaseUrl=https://你的已备案API域名"
        }
        require(devAuthOpenId.isBlank()) { "Release 构建禁止设置 -PdevAuthOpenId" }
        require(hasReleaseKeystore) {
            "Release 构建缺少 keystore.properties / keystore，请在仓库根目录配置 keystore.properties（密钥口令），并把发布密钥放入 keystore/ 目录"
        }
    }
}

dependencies {
    // 全部选用本机 Gradle 缓存已有版本，避免重复下载；也刻意不引入 Compose/Material 以压低包体
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
