package com.notdisk.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

internal const val COMPILE_SDK = 35

/**
 * Android 15 (API 35) is the floor.
 *
 * A deliberate product decision rather than an oversight. The app targets one current platform so
 * that edge-to-edge rendering, the predictive-back gesture and the platform's enforced window insets
 * are *the* behaviour rather than one of several branches; supporting API 26 would mean carrying a
 * second, permanently untested layout path for a device class this build does not target.
 *
 * A consequence worth naming: the dynamic-colour check in `NotDiskTheme` and the runtime-permission
 * branches elsewhere are now statically true, so those paths are the only ones that exist.
 */
internal const val MIN_SDK = 35
internal const val TARGET_SDK = 35

internal val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
internal val KOTLIN_JVM_TARGET: JvmTarget = JvmTarget.JVM_17

/**
 * The project's version catalog.
 *
 * Resolved at runtime rather than through a generated `libs` accessor: the catalog lives in the
 * main build (`gradle/libs.versions.toml`) and is re-exported into `build-logic` via
 * `settings.gradle.kts`, and generated accessors are only produced for the default location.
 */
internal val Project.catalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Resolves a library alias, failing loudly (with the alias name) when it is not declared. */
internal fun Project.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    catalog.findLibrary(alias).orElseThrow {
        IllegalStateException(
            "Library alias '$alias' is not declared in gradle/libs.versions.toml",
        )
    }
