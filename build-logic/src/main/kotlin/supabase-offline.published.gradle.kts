// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAbiValidation::class)

import io.github.nesfeder.supabase.offline.buildlogic.parseAbiExclusionMarkers
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

// supabase-offline.published: this module ships, so it carries the library's Maven identity and
// its public ABI is recorded. The group and version also name the klib (`<group>:<module>`).
// Publishing itself is set up elsewhere.

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

// The public ABI records: api/android/<module>.api for the Android target and api/<module>.klib.api
// for the iOS targets. `checkKotlinAbi`, which `check` runs, fails when they are stale;
// `updateKotlinAbi` rewrites them. Declarations gated by a marker in compat/abi-exclusions.txt are
// left out; scripts/check-api-visibility.sh keeps that list equal to the error-level markers. The
// tool is experimental, and the @file:OptIn above is the build's only opt-in to it.
private val abiExclusionsFile = "compat/abi-exclusions.txt"

private val abiExclusionMarkers: List<String> =
    parseAbiExclusionMarkers(
        text =
            providers
                .fileContents(isolated.rootProject.projectDirectory.file(abiExclusionsFile))
                .asText
                .orNull ?: throw GradleException("$path: $abiExclusionsFile is missing"),
        fileName = abiExclusionsFile,
    )

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        abiValidation {
            filters { exclude { annotatedWith.addAll(abiExclusionMarkers) } }
            // A target this machine cannot compile fails the record instead of being copied from
            // the committed one, which would hide exactly the change the record exists to show.
            keepLocallyUnsupportedTargets.set(false)
        }
    }
}
