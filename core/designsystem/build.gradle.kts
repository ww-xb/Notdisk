plugins {
    id("notdisk.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.notdisk.core.designsystem"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    // The file tiles accept a nullable `thumbnailUrl`; rendering a remote URL requires an image
    // loader, so it is part of this module's public surface rather than each feature's problem.
    api(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
