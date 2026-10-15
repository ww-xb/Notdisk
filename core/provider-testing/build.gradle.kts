plugins {
    id("notdisk.kotlin.jvm")
}

dependencies {
    api(project(":core:provider-api"))
    api(project(":core:model"))
    api(project(":core:common"))
    api(libs.junit)
    api(libs.kotlin.test)
    api(libs.kotlinx.coroutines.test)
    api(libs.kotlinx.coroutines.core)
    api(libs.turbine)
}
