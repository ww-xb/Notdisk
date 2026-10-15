// 阿里云镜像仅在受限网络（如中国大陆开发机）需要。
// 默认官方仓库优先、镜像兜底；受限网络可设 NOTDISK_MAVEN_MIRROR=aliyun 让镜像优先，
// 避免逐个官方仓库超时。CI（GitHub Actions）不设该变量，即不依赖镜像。
// 注意：pluginManagement 块先于脚本主体求值，top-level val 在其中不可见，故内联条件。
pluginManagement {
    includeBuild("build-logic")
    val mirrorFirst = providers.environmentVariable("NOTDISK_MAVEN_MIRROR").orNull == "aliyun"
    repositories {
        if (mirrorFirst) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
        if (!mirrorFirst) {
            // 兜底：官方仓库偶发超时时仍可走镜像。
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val mirrorFirst = providers.environmentVariable("NOTDISK_MAVEN_MIRROR").orNull == "aliyun"
    repositories {
        if (mirrorFirst) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        if (!mirrorFirst) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
    }
}

rootProject.name = "NotDisk"

// ---- Application ----
include(":app")

// ---- Core ----
include(":core:common")
include(":core:model")
include(":core:provider-api")
include(":core:provider-testing")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:domain")
include(":core:designsystem")

// ---- Providers ----
include(":provider:mock")
include(":provider:p115")

// ---- Features ----
include(":feature:accounts")
include(":feature:files")
include(":feature:player")
include(":feature:preview")
include(":feature:settings")
