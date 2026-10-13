// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAbiValidation::class)

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import io.github.nesfeder.supabase.offline.buildlogic.parseAbiExclusionMarkers
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.GradleException
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.Sign
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

// supabase-offline.published: this module ships, so it carries the library's Maven identity, its
// public ABI is recorded, its coverage is measured, and it is published to Maven Central with the
// same POM fields, documentation and signing as every other published module. The group and
// version also name the klib (`<group>:<module>`).

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
        // An interface member with a body compiles to a JVM default method and nothing else. The
        // default mode also emits a `$DefaultImpls` class per such interface, which puts a
        // supertype's defaults (supabase-kt's `BucketApi` included) into this module's ABI.
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            compilerOptions { jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY) }
        }
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

// Maven Central (RELEASING.md): the POM from the POM_* properties of gradle.properties (a module's
// own gradle.properties overrides the root's), the sources and an HTML reference in every
// artifact's jars, and a signature on every file when the signing key is given.
pluginManager.apply("org.jetbrains.dokka")

pluginManager.apply("com.vanniktech.maven.publish.base")

// The commit the site's reference links its sources to (-Psupabase.offline.sourceCommit=<SHA>),
// which only the docs workflows pass. Without it no source link is written, so the published
// documentation jars never carry one.
private val sourceCommit: String? =
    providers.gradleProperty("supabase.offline.sourceCommit").orNull?.also { commit ->
        if (!Regex("[0-9a-f]{40}").matches(commit)) {
            throw GradleException(
                "-Psupabase.offline.sourceCommit is \"$commit\", not a full commit SHA"
            )
        }
    }

// The reference documents the public packages only. Everything under `…offline.internal` is left
// out, including the public declarations that exist for another module of this project.
extensions.configure<DokkaExtension> {
    moduleName.set(project.name)
    // Dokka gives an inherited member no page and links each overload of one to an anchor it never
    // writes, as on OfflineBucketApi's page. So inherited members are left out: each is listed on
    // the type that declares it, and supabase-kt's reference, linked below, documents its own.
    dokkaPublications.configureEach { suppressInheritedMembers.set(true) }
    // Dokka resolves a link through a source set's direct dependencies only, so without this a
    // commonMain type named in iosMain, three levels below it, links to an anchor no page has.
    dokkaSourceSets
        .matching { it.name == "iosMain" }
        .configureEach {
            dependentSourceSets.add(dokkaSourceSets.getByName("commonMain").sourceSetId.get())
        }
    dokkaSourceSets.configureEach {
        // The supabase-kt types the reference names link to supabase-kt's own reference.
        externalDocumentationLinks.register("supabase-kt") {
            url("https://supabase-community.github.io/supabase-kt/")
        }
        perPackageOption {
            matchingRegex.set("""io\.github\.nesfeder\.supabase\.offline\.internal(\..*)?""")
            suppress.set(true)
        }
        if (sourceCommit != null) {
            sourceLink {
                localDirectory.set(isolated.rootProject.projectDirectory)
                remoteUrl("${requiredGradleProperty("POM_SCM_URL")}/blob/$sourceCommit")
                remoteLineSuffix.set("#L")
            }
        }
    }
}

extensions.configure<MavenPublishBaseExtension> {
    // `publishToMavenCentral` uploads a deployment for the owner to release or drop in the Central
    // Portal (the dry run); `publishAndReleaseToMavenCentral` uploads and releases it (the real
    // run). The explicit `false` keeps that split whatever a Gradle property says.
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
    pomFromGradleProperties()
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
        )
    )
}

/** Whether the Gradle property is set to something other than blank. */
private fun hasGradleProperty(name: String): Boolean =
    providers.gradleProperty(name).orNull?.isNotBlank() == true

// Signing uses only the in-memory key, the Gradle properties signingInMemoryKey and
// …KeyPassword (ORG_GRADLE_PROJECT_<name> in the release job's environment). Without the key
// nothing is signed, so a key ring named by `signing.*` properties is never used, and a build
// without secrets still publishes to the build directory.
if (!hasGradleProperty("signingInMemoryKey")) {
    tasks.withType<Sign>().configureEach { enabled = false }
}

// Maven Central needs the Portal token (the Gradle properties mavenCentralUsername and …Password),
// and a release version also the signing key; a blank value counts as missing. Checked before a
// publish task writes anything; the upload happens at the end of a build that succeeded, so
// nothing is sent.
tasks
    .withType<PublishToMavenRepository>()
    .matching { it.name.endsWith("ToMavenCentralRepository") }
    .configureEach {
        val releaseVersion = !version.toString().endsWith("-SNAPSHOT")
        val missing = buildList {
            listOf("mavenCentralUsername", "mavenCentralPassword").forEach { property ->
                if (!hasGradleProperty(property)) add(property)
            }
            if (releaseVersion && !hasGradleProperty("signingInMemoryKey")) {
                add("signingInMemoryKey")
            }
        }
        doFirst {
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Publishing to Maven Central needs the Gradle properties " +
                        "${missing.joinToString()} (ORG_GRADLE_PROJECT_<name> in the environment)"
                )
            }
        }
    }

// A repository inside the build directory, for checking what would be published without sending
// it anywhere: scripts/check-published-artifacts.py runs
// `publishAllPublicationsToBuildDirectoryRepository` and inspects the result.
extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "buildDirectory"
            url = uri(isolated.rootProject.projectDirectory.dir("build/maven-repository"))
        }
    }
}

// The system-SQLite variant builds the Swift package and runs the iOS suite on Apple's SQLite. Its
// iOS publications would hand a Maven consumer Apple's SQLite without the -lsqlite3 it needs, in
// place of the bundled SQLite every published version carries, so no publish task may run with it.
if (providers.gradleProperty("supabase.offline.iosSqlite").orNull == "system") {
    tasks.withType<AbstractPublishToMaven>().configureEach {
        doFirst {
            throw GradleException(
                "Publishing is disabled with -Psupabase.offline.iosSqlite=system: that build is " +
                    "for the Swift package and the system-SQLite test run only. Publish from a " +
                    "build without the property."
            )
        }
    }
}
