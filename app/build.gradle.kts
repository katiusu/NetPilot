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
    namespace = "io.github.katiusu.netpilot"
    // Miuix 0.9.5 的 AAR 元数据要求 compileSdk >= 37.2，故用 release(37){minorApiLevel=2}；
    // build-tools 固定 36.0.0：SDK 上 37.0.0 只有 x86_64 产物，本容器（aarch64 + PRoot）跑不了，
    // 而 36.0.0 内置的 aapt2 是 aarch64 且能正常解析 android-37.0 的 resources.arsc。
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.katiusu.netpilot"
        // minSdk 33（Android 13）：液态玻璃与预测性返回都依赖 API 33 的 RuntimeShader /
        // setEnableOnBackInvokedCallback，33 是这套 UI 的最低可用版本，再低就得给全部
        // glass 组件写降级路径。manifest 里的 specialUse 前台服务类型（API 34）在 33 上
        // 被系统忽略，只是退化成无类型前台服务，不影响启动与保活。
        minSdk = 33
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
        versionCode = 26
        // versionName 是给人看的语义版本；versionCode 是构建号（2026100505 = 2026-10-05 第 5 次构建），
        // 两者都要有：报问题时给构建号才能精确定位到某一次构建。
        // 1.5.1：把「provider 不认调用方身份」与「真的缺权限」分开（uid 名单 / AMS 应用进程记录 /
        // ACCESS_TELEPHONY_SIMINFO_DB 三种成因各自的原文与解释都写准）、读不到时枚举整张 siminfo 表、
        // 默认数据 subId 为空时按候选卡补齐、日志页倒序、每一步失败都带原因。
        // 注意：2026100505 这个构建号曾被一个从未发布的 1.6.0 构建用过（同一把签名 key），
        // 所以本版可以直接覆盖安装那个包。
        // 2.0.0-rc.3：24 条缺陷修复（广播鉴权与回执定向、降级态失败不再丢记录、
        //   双卡降级前制式持久化、命令行读写超时、监控停止不再被服务自身翻回）+
        //   基带只读探测（高通/联发科/三星/紫光，不做写入）。
        // 2.0.0：底栏形态（柔光玻璃 / 液态玻璃）与宽屏侧栏（无 / 顶部 / 左侧）改为枚举偏好，
        //   替换原先能组合出非法状态的两个布尔；日志页溢出菜单改用玻璃卡片；
        //   设置页与关于页都能看当前版本更新日志（changelog.md 打包进 assets）；
        //   预测性返回开关；增加繁体中文（zh-rTW）。
        // 1.5.4：锁屏期间保活心跳换成不唤醒闹钟（深睡）+ 手动切换制式后回读确认并如实记录
        //   + Shizuku 晚于应用启动时也能清掉残留的用户服务进程。
        // 2.0.1：缺陷修复（预发布版号被当成更新、预测性返回开关反射打错类、切回跟随系统语言不还原、
//   配置导入的 null 流与非法数组元素）。
        // 2.0.2：省电（息屏静止退避、系统省电模式感知、采样间隔翻倍）
        //   与流畅度（动画默认跟随刷新率、省电模式锁 60 帧、省电模式关毛玻璃）。
        versionName = "2.0.3"

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

    implementation(libs.graphics.shapes)
    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.navigation)
    implementation(libs.miuix.material)
    implementation(libs.miuix.glass)
    implementation(libs.miuix.glass.icons)
    implementation(libs.material.icons)
    implementation(libs.haze)
    // Shizuku：api 提供 Shizuku.newProcess / 权限接口，provider 提供 ContentProvider 授权入口
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
}
