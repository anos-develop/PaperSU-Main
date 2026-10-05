// paperSU 网页壳：打开后停留 3 秒，然后自动跳到网页管理端。
// 刻意不引任何第三方依赖：只用 Android 框架类，避免依赖解析问题。
plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.apksign)
}

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "top.becuy.eric.papersu.web"
    compileSdk = rootProject.extra["androidCompileSdkVersion"] as Int

    defaultConfig {
        applicationId = "top.becuy.eric.papersu.web"
        minSdk = rootProject.extra["androidMinSdkVersion"] as Int
        targetSdk = rootProject.extra["androidTargetSdkVersion"] as Int
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
        targetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion
    }
}