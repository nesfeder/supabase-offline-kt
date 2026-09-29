// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import org.gradle.api.GradleException

// supabase-offline.published: this module ships, so it carries the library's Maven identity. The
// group and version also name the klib (`<group>:<module>`). Publishing itself is set up elsewhere.

/** A property of the root gradle.properties that every published module needs. */
private fun requiredGradleProperty(key: String): String =
    providers.gradleProperty(key).orNull
        ?: throw GradleException("$path: gradle.properties has no $key")

group = requiredGradleProperty("GROUP")

version = requiredGradleProperty("VERSION_NAME")

// The Kotlin plugin names the klib and every publication after the project, so the artifact
// name declared in the module's gradle.properties must be the project's name.
private val artifactName = findProperty("POM_ARTIFACT_ID")?.toString()

if (artifactName != name) {
    throw GradleException(
        "$path: POM_ARTIFACT_ID in its gradle.properties is \"$artifactName\"; a published " +
            "module declares its artifact name there, and it must be the project name \"$name\""
    )
}
