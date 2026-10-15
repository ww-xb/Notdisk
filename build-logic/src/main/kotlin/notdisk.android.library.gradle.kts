import com.notdisk.buildlogic.COMPILE_SDK
import com.notdisk.buildlogic.JAVA_VERSION
import com.notdisk.buildlogic.KOTLIN_JVM_TARGET
import com.notdisk.buildlogic.MIN_SDK
import com.notdisk.buildlogic.lib
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

extensions.configure<LibraryExtension> {
    compileSdk = COMPILE_SDK

    defaultConfig {
        minSdk = MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JAVA_VERSION
        targetCompatibility = JAVA_VERSION
    }

    buildFeatures {
        buildConfig = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }

    lint {
        abortOnError = false
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(KOTLIN_JVM_TARGET)
    }
}

dependencies {
    // Standard test stack shared by every Android module in the project.
    add("testImplementation", lib("junit"))
    add("testImplementation", lib("kotlin-test"))
    add("testImplementation", lib("kotlinx-coroutines-test"))
    add("testImplementation", lib("mockk"))
    add("testImplementation", lib("turbine"))
    add("androidTestImplementation", lib("androidx-test-ext-junit"))
    add("androidTestImplementation", lib("androidx-test-espresso-core"))
}
