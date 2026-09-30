// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAbiValidation::class)

import io.github.nesfeder.supabase.offline.buildlogic.parseAbiExclusionMarkers
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

// supabase-offline.published: this module ships, so it carries the library's Maven identity, its
// public ABI is recorded and its coverage is measured. The group and version also name the klib
// (`<group>:<module>`). Publishing itself is set up elsewhere.

/** A property of the root gradle.properties that every published module needs. */
private fun requiredGradleProperty(key: String): String =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException("The root gradle.properties has no $key, which $path needs")

// The build-tool floors, which every role plugin applies.
pluginManager.apply("supabase-offline.build-tool-floors")

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

// Coverage of the Android host tests: Kover instruments JVM test runs only, so iOS-only code is
// never measured. Each module reports its own classes; `koverXmlReport` writes
// build/reports/kover/report.xml, and `check` runs `koverVerify`.
pluginManager.apply("org.jetbrains.kotlinx.kover")

extensions.configure<KoverProjectExtension> {
    reports {
        filters {
            excludes {
                // Room's KSP output: the DAO and database implementations, their inner classes and
                // the object that implements the database's expect constructor.
                classes(
                    "io.github.nesfeder.supabase.offline.internal.db.*_Impl",
                    "io.github.nesfeder.supabase.offline.internal.db.*_Impl\$*",
                    "io.github.nesfeder.supabase.offline.internal.db.OfflineDatabaseConstructor",
                )
                // Bridges the compiler generates for interface default methods; they only delegate.
                classes("*\$DefaultImpls")
            }
        }
        verify {
            // A tripwire for broken measurement, such as a Kover release that reports 0%, not a
            // quality bar: the real figure is far above it.
            rule("Coverage data reached the report") { minBound(50) }
        }
    }
}
