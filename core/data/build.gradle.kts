plugins {
    id("notdisk.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    // Needed for the reified `Json.encodeToString<T>` / `decodeFromString<T>` used by the metadata
    // cache: without the plugin those overloads cannot be resolved.
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.notdisk.core.data"
}

dependencies {
    api(project(":core:provider-api"))
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:database"))
    api(project(":core:network"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
}
