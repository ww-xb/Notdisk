plugins {
    id("notdisk.android.library")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.notdisk.core.database"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.room.testing)
}
