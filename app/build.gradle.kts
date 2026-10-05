import java.util.Properties

plugins {
    // AGP 9.x 内置 Kotlin 支持，无需再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 签名配置：release.keystore 与 keystore.properties 都在 .gitignore 里（私钥绝不进仓库）。
// 缺 keystore.properties 时（别人克隆仓库、CI 没配密钥）回落到 debug 签名，保证 assembleRelease 仍能出包。
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKeystore) keystorePropsFile.inputStream().use { load(it) }
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
        // targetSdk 36（Android 16）。Play 的 targetSdk 36 提交要求已于 2026-08-31 生效
        // （可申请延期到 2026-11-01），继续用 34 已经无法提交更新。本应用已满足 36 的全部强制项：
        //   * edge-to-edge —— 5 个 Activity 都调了 enableEdgeToEdge()，manifest 里也没有
        //     Android 16 已移除的 windowOptOutEdgeToEdgeEnforcement；
        //   * 预测性返回 —— 已 enableOnBackInvokedCallback="true"，且全工程没有
        //     onBackPressed / BackHandler 拦截；
        //   * FGS specialUse —— PROPERTY_SPECIAL_USE_FGS_SUBTYPE 已声明；Android 15 的
        //     6 小时 FGS 超时与「BOOT_COMPLETED 不能启动的 6 类 FGS」都不含 specialUse；
        //   * 16 KB 页大小 —— 2026-10-05 实测通过（zipalign -c -P 16 -v 4 报 Verification successful，
        //     全部 .so 的 LOAD 段 p_align = 16384）；升级 AGP/NDK 后需重跑这条命令。
        // compileSdk 保持 37：AGP 要求 compileSdk >= targetSdk，且 compileSdk 只决定编译期
        // 能调用的 API 面，不改变运行时行为（运行时行为由 targetSdk 决定）。
        targetSdk = 36
        versionCode = 2026100600
        // versionName 是给人看的语义版本；versionCode 是构建号（2026100505 = 2026-10-05 第 5 次构建），
        // 两者都要有：报问题时给构建号才能精确定位到某一次构建。
        // 1.5.1：把「provider 不认调用方身份」与「真的缺权限」分开（uid 名单 / AMS 应用进程记录 /
        // ACCESS_TELEPHONY_SIMINFO_DB 三种成因各自的原文与解释都写准）、读不到时枚举整张 siminfo 表、
        // 默认数据 subId 为空时按候选卡补齐、日志页倒序、每一步失败都带原因。
        // 注意：2026100505 这个构建号曾被一个从未发布的 1.6.0 构建用过（同一把签名 key），
        // 所以本版可以直接覆盖安装那个包。
        versionName = "1.5.2"

    }

    // release 构建默认会跑 lintVital（只看 fatal 问题）。本容器 metaspace 上限 320m，
    // 它会在 lintVitalAnalyzeRelease 里抛 OutOfMemoryError: Metaspace（与代码无关）。
    // 关掉 release 构建的 lint 门禁；需要检查时单独跑 :app:lintDebug。
    lint {
        checkReleaseBuilds = false
    }

    // 正式签名（v2/v3 由 AGP 按 minSdk 自动决定，minSdk 34 不需要 v1）
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 有密钥就用正式签名，否则沿用 debug（保证 assembleRelease 也能出包）
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // 与参考模板 MiuixGuiExample（/sdcard/Project/MiuixGui）的 release 配置一致：
            // R8 代码压缩 + proguard-android-optimize 优化。
            // 为什么本版才开：示例工程把「切页掉帧」逐页对比过，页面代码逐行相同 —— 掉帧
            // 不是页面写法问题，而是构建层没做优化（debug 式 dex：无内联、无裁剪、类/常量池
            // 全量保留）。因此 1.5.2 把为流畅度做的写法改动全部回滚，改由这里承担。
            //
            // 注意：本工程有两处入口是**由外部进程按类名加载**的（root 的 app_process
            // 命令行、Shizuku 用户服务），keep 规则见 app/proguard-rules.pro；漏了不会编译
            // 报错，只会在真机上静默失效（自检失败 / 用户服务绑不上）。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
