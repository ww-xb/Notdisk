plugins {
    id("notdisk.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.notdisk.feature.files"

    testOptions {
        unitTests.all {
            // Robolectric fetches its `android-all` runtime through its OWN resolver, which does not
            // consult Gradle repositories. Only pin the Aliyun mirror on restricted networks
            // (NOTDISK_MAVEN_MIRROR=aliyun); everywhere else use Robolectric's default Maven Central.
            if (providers.environmentVariable("NOTDISK_MAVEN_MIRROR").orNull == "aliyun") {
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven.aliyun.com/repository/public",
                )
                it.systemProperty("robolectric.dependency.repo.id", "aliyun-public")
            }
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
    // `NotDiskSettingsStore` is declared in `core:database` (settings package) and only
    // re-exported through `core:data`'s DI graph, not through its API surface. The files screen
    // persists its view mode and sort choice there, so the type must be on the compile classpath.
    implementation(project(":core:database"))
    // The in-memory provider from `provider:mock` backs the FilesViewModel unit tests: its
    // file-system semantics (paging, latency, typed failures) are exactly what the stale-response
    // and loadMore guards need to be exercised against. Test-only — never shipped.
    testImplementation(project(":provider:mock"))
    // The settings store is backed by DataStore, and the tests construct a real one (over a temp
    // file) because `activeAccount` is the mechanism that decides which account the screen opens.
    testImplementation(libs.androidx.datastore.preferences)
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
