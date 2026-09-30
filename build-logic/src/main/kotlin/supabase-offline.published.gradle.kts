// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import org.gradle.api.GradleException

// supabase-offline.published: this module ships, so it carries the library's Maven identity. The
// group and version also name the klib (`<group>:<module>`). Publishing itself is set up elsewhere.

/** A property of the root gradle.properties that every published module needs. */
private fun requiredGradleProperty(key: String): String =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException("The root gradle.properties has no $key, which $path needs")

group = requiredGradleProperty("GROUP")

version = requiredGradleProperty("VERSION_NAME")

// The artifact name, POM_ARTIFACT_ID in the module's own gradle.properties, must be the project
// name: the Kotlin plugin names the klib and every publication after the project.
private val artifactName: String =
    findProperty("POM_ARTIFACT_ID")?.toString()?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
            "$path: its gradle.properties must name the artifact (POM_ARTIFACT_ID)"
        )

if (artifactName != name) {
    throw GradleException(
        "$path: POM_ARTIFACT_ID is \"$artifactName\", not the project name \"$name\""
    )
}
