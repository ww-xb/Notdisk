plugins {
    id("notdisk.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.notdisk.feature.accounts"

    testOptions {
        unitTests.all {
            // Robolectric fetches its `android-all` runtime through its OWN resolver, which does not
            // consult Gradle repositories; without this it fails where Maven Central is blocked.
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://maven.aliyun.com/repository/public",
            )
            it.systemProperty("robolectric.dependency.repo.id", "aliyun-public")
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
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
