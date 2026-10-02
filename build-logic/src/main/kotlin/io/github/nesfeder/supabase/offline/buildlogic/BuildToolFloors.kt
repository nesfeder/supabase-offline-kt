// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import org.gradle.api.Project

/** How the catalog's `buildToolFloor-*` aliases start, in the dotted form Gradle lists them. */
private const val BUILD_TOOL_FLOOR_ALIAS_PREFIX = "buildToolFloor."

/**
 * Constrains this project's configurations named [configurationNames], or starting with one of
 * [configurationNamePrefixes], to every build-tool floor in the catalog, whenever a plugin creates
 * them. A constraint only ever raises a version.
 */
internal fun Project.constrainToBuildToolFloors(
    configurationNames: Set<String>,
    configurationNamePrefixes: Set<String> = emptySet(),
) {
    val floors =
        versionCatalog.libraryAliases
            .filter { it.startsWith(BUILD_TOOL_FLOOR_ALIAS_PREFIX) }
            .map { catalogLibrary(it) }
    val constraints = dependencies.constraints
    configurations
        .matching { configuration ->
            configuration.name in configurationNames ||
                configurationNamePrefixes.any { configuration.name.startsWith(it) }
        }
        .configureEach {
            val configurationName = name
            floors.forEach { floor -> constraints.add(configurationName, floor) }
        }
}
