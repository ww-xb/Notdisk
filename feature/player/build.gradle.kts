plugins {
    id("notdisk.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.notdisk.feature.player"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:provider-api"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))

    // ---- The player's own rendering stack. ----
    // Media3 is declared here (and not only in `:app`) because this module is the only place that
    // builds a MediaSource. Nothing vendor-specific is declared, and nothing vendor-specific ever
    // may be: `feature:player` is the module the architecture's "no provider knowledge in the
    // player" acceptance criterion is written about. See the module-invariant KDoc at the top of
    // `PlayerScreen.kt`, and `PlayerModuleIsolationTest`, which fails the build if a `provider:`
    // project dependency is ever added below.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
