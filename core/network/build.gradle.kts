plugins {
    id("notdisk.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.notdisk.core.network"
}

dependencies {
    api(project(":core:common"))
    api(libs.okhttp)
    api(libs.okhttp.logging)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.okhttp.mockwebserver)
}
