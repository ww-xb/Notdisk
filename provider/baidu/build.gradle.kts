plugins {
    id("notdisk.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.notdisk.provider.baidu"

    testOptions {
        unitTests.all {
            // A provider test that hangs is worse than one that fails: it hides the bug and stalls
            // CI. Serial execution keeps the MockWebServer ports and the shared fixture state
            // deterministic.
            it.maxParallelForks = 1
        }
    }
}

dependencies {
    api(project(":core:provider-api"))
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    testImplementation(project(":core:provider-testing"))
    testImplementation(libs.okhttp.mockwebserver)
}
