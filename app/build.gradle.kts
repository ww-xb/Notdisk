plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.notdisk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.notdisk.app"
        // Android 15+ only. See `build-logic/.../ProjectExtensions.kt` for why the floor is 35.
        minSdk = 35
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }

    lint { abortOnError = false }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:provider-api"))
    implementation(project(":core:designsystem"))
    // Shipped providers. The offline demo provider (`provider:mock`) was removed from the build
    // entirely: a provider that fabricates accounts and files must not be reachable from a release
    // APK, and keeping it behind a build flag would leave a fake-account code path one property away
    // from shipping.
    implementation(project(":provider:p115"))
    implementation(project(":provider:baidu"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:files"))
    implementation(project(":feature:player"))
    implementation(project(":feature:preview"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.test.core.ktx)
    kspAndroidTest(libs.hilt.compiler)
}
