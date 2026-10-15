plugins {
    id("notdisk.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.notdisk.provider.p115"

    testOptions {
        unitTests.all {
            // A provider test that hangs is worse than one that fails: it hides the bug and stalls
            // CI. A hard timeout turns "the code is waiting on a socket that will never answer"
            // into a named, diagnosable failure. AGP's `unitTests.all` exposes a
            // Test task, whose Kotlin DSL accessor is `setTimeout` via `timeout` only on newer
            // Gradle; the safe, version-independent way is `it.timeout.set(...)` on the task.
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
