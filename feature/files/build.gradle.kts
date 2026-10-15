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
            // Where `SkeletonScreenshotTest` writes its renderings. Forwarded from Gradle only when
            // actually supplied, so the documented `-Dnotdisk.screenshot.dir=...` invocation produces
            // reviewable files in the repository while an ordinary run leaves the test on its own
            // writable default (which is what keeps it green on a CI runner).
            providers.systemProperty("notdisk.screenshot.dir").orNull?.takeIf { it.isNotBlank() }
                ?.let { dir -> it.systemProperty("notdisk.screenshot.dir", dir) }
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
    // The settings store is backed by DataStore, and the tests construct a real one (over a temp
    // file) because `activeAccount` is the mechanism that decides which account the screen opens.
    testImplementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    // The content cross-fade, the inline-failure expand/shrink and the progress bar's alpha are all
    // `androidx.compose.animation`. It does arrive transitively (foundation exposes it as an `api`
    // dependency), but a module that imports a package directly should say so: relying on someone
    // else's `api` edge is how a dependency upgrade silently breaks a module that never declared it.
    implementation(libs.androidx.compose.animation)
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
