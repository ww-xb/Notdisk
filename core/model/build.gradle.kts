plugins {
    id("notdisk.kotlin.jvm")
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
}
