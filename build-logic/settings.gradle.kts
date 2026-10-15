// 与根 settings.gradle.kts 保持一致：默认官方仓库优先、阿里云镜像兜底；
// 受限网络设 NOTDISK_MAVEN_MIRROR=aliyun 让镜像优先。
// 注意：pluginManagement 块先于脚本主体求值，top-level val 在其中不可见，故内联条件。
pluginManagement {
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
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
    }
}

dependencyResolutionManagement {
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
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
