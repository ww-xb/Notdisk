plugins {
    id("notdisk.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.notdisk.provider.mock"
}

dependencies {
    api(project(":core:provider-api"))
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(project(":core:provider-testing"))
}
