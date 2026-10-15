pluginManagement {
    includeBuild("build-logic")
    repositories {
        // Mirrors first for network reliability in this build environment.
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
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
