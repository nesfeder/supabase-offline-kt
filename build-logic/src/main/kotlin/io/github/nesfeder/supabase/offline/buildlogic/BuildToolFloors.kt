// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import org.gradle.api.Project

/** How the catalog's `buildToolFloor-*` aliases start, in the dotted form Gradle lists them. */
private const val BUILD_TOOL_FLOOR_ALIAS_PREFIX = "buildToolFloor."

/**
 * Adds every build-tool floor in the catalog as a dependency constraint to this project's
 * configurations named [configurationNames], whenever a plugin creates them. A constraint only ever
 * raises a version, so a plugin that brings a newer one keeps it.
 */
internal fun Project.constrainToBuildToolFloors(configurationNames: Set<String>) {
    val floors =
        versionCatalog.libraryAliases
            .filter { it.startsWith(BUILD_TOOL_FLOOR_ALIAS_PREFIX) }
            .map { catalogLibrary(it) }
    val constraints = dependencies.constraints
    configurations
        .matching { it.name in configurationNames }
        .configureEach {
            val configurationName = name
            floors.forEach { floor -> constraints.add(configurationName, floor) }
        }
}
