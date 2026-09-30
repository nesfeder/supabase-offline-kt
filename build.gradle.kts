// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
buildscript {
    // The build-tool floors (gradle/libs.versions.toml, `buildToolFloor-*`) for what the plugins
    // below bring onto this classpath. A constraint only ever raises a version.
    dependencies {
        constraints {
            val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
            catalog.libraryAliases
                .filter { it.startsWith("buildToolFloor.") }
                .forEach { classpath(catalog.findLibrary(it).get()) }
        }
    }
}

plugins {
    // Every plugin a module applies, itself or through a convention plugin in build-logic, is
    // loaded here once, so all projects share one copy of it.
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.skie) apply false
}
