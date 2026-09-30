// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * `gradle/libs.versions.toml`, read from this project's own extension. Precompiled script plugins
 * get no `libs` accessor, and reading the root project's would reach across projects.
 */
internal val Project.versionCatalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** The catalog's library [alias], written as it is in the TOML file (`androidx-room-compiler`). */
internal fun Project.catalogLibrary(alias: String): Provider<MinimalExternalModuleDependency> =
    versionCatalog.findLibrary(alias).orElseThrow {
        IllegalStateException("gradle/libs.versions.toml has no library \"$alias\"")
    }

/** The catalog's version [alias], written as it is in the TOML file (`android-minSdk`). */
internal fun Project.catalogVersion(alias: String): String =
    versionCatalog
        .findVersion(alias)
        .orElseThrow {
            IllegalStateException("gradle/libs.versions.toml has no version \"$alias\"")
        }
        .requiredVersion
