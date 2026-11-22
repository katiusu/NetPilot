pluginManagement {
    // GitHub Actions 直连官方仓库：阿里云镜像偶发 502，而 Gradle 对 5xx 不会降级到下一个
    // 仓库，会把整次构建打死（run 44964249449 即因此失败）。本地构建仍走镜像。
    val useAliyunMirror = System.getenv("GITHUB_ACTIONS") != "true"
    repositories {
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
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
    // GitHub Actions 直连官方仓库：阿里云镜像偶发 502，而 Gradle 对 5xx 不会降级到下一个
    // 仓库，会把整次构建打死（run 44964249449 即因此失败）。本地构建仍走镜像。
    val useAliyunMirror = System.getenv("GITHUB_ACTIONS") != "true"
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "NetPilot"
include(":app")
