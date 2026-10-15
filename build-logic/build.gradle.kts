plugins {
    `kotlin-dsl`
}

group = "com.notdisk.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // `implementation` (not `compileOnly`): the precompiled script plugins below apply these
    // plugin ids, so their implementations must be present on the build-logic runtime classpath.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
}
