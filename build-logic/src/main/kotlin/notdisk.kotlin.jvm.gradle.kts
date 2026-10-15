import com.notdisk.buildlogic.JAVA_VERSION
import com.notdisk.buildlogic.KOTLIN_JVM_TARGET
import com.notdisk.buildlogic.lib
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
}

extensions.configure<KotlinJvmProjectExtension> {
    compilerOptions {
        jvmTarget.set(KOTLIN_JVM_TARGET)
    }
}

java {
    sourceCompatibility = JAVA_VERSION
    targetCompatibility = JAVA_VERSION
}

dependencies {
    add("testImplementation", lib("junit"))
    add("testImplementation", lib("kotlin-test"))
    add("testImplementation", lib("kotlinx-coroutines-test"))
    add("testImplementation", lib("mockk"))
    add("testImplementation", lib("turbine"))
}
