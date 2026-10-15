plugins {
    id("notdisk.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.notdisk.feature.preview"

    testOptions {
        unitTests.all {
            // Robolectric downloads its `android-all` runtime jar through its OWN resolver, which does
            // not consult the Gradle repositories — so in an environment where Maven Central is
            // blocked it fails even though the artifact is reachable from the configured mirror.
            // `robolectric.dependency.repo.url` is the documented override.
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://maven.aliyun.com/repository/public",
            )
            it.systemProperty("robolectric.dependency.repo.id", "aliyun-public")
            // Silence the "no @Config sdk" warning path and keep the run reproducible.
            it.systemProperty("robolectric.logging", "stdout")
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:provider-api"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    // The shared OkHttp factory lives here and is not re-exported by `:core:data` (which consumes it
    // as `implementation`). The previewer is the first non-provider consumer that needs a bare HTTP
    // client for its bounded text fetch, so it declares the dependency explicitly.
    implementation(project(":core:network"))

    // ---- The previewer's own rendering stack. ----
    // Coil renders images; `coil-network-okhttp` is the network layer that lets an image request
    // carry the signed-resource headers (`Referer`/`Origin`/`User-Agent`) some providers require,
    // and OkHttp is used directly for the bounded text fetch in `PreviewViewModel`.
    // No provider module is declared here, and none ever may be: the preview surface consumes only
    // `PreviewResult` — see the module-invariant KDoc at the top of `PreviewScreen.kt`.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    // Robolectric runs the real Compose runtime on the JVM. It is the only way to exercise Compose
    // semantics in this environment, which has no KVM and therefore cannot boot an emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
