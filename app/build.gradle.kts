plugins {
    // AGP 9.x 内置 Kotlin 支持，无需再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.katiusu.netpilot"
    // Miuix 0.9.4 的 AAR 元数据要求依赖方 compileSdk >= 37，故用 37；
    // build-tools 固定 36.0.0：SDK 上 37.0.0 只有 x86_64 产物，本容器（aarch64 + PRoot）跑不了，
    // 而 36.0.0 内置的 aapt2 是 aarch64 且能正常解析 android-37.0 的 resources.arsc。
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.katiusu.netpilot"
        minSdk = 34
        targetSdk = 34
        versionCode = 2026100400
        // versionName 是给人看的语义版本；versionCode 是构建号（2026100400），
        // 两者都要有：报问题时给构建号才能精确定位到某一次构建。
        versionName = "1.0.0"

    }

    buildTypes {
        release {
            // 仓库内没有 release.keystore，签名沿用 debug，保证 assembleRelease 也能出包
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Shizuku 用户服务走 AIDL（IShizukuController），必须显式开启
        aidl = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.shader)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.navigation)
    implementation(libs.material.icons)
    implementation(libs.haze)
    // Shizuku：api 提供 Shizuku.newProcess / 权限接口，provider 提供 ContentProvider 授权入口
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
}
