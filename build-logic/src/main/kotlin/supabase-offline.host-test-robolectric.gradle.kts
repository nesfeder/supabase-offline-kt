// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import io.github.nesfeder.supabase.offline.buildlogic.RobolectricOfflineArguments
import io.github.nesfeder.supabase.offline.buildlogic.SyncRobolectricFrameworkJars
import io.github.nesfeder.supabase.offline.buildlogic.catalogLibrary

// supabase-offline.host-test-robolectric: this module's Robolectric tests run on an Android
// framework jar (org.robolectric:android-all-instrumented) that Robolectric would otherwise
// download itself, from Maven Central into ~/.m2, when the first test class starts: one attempt,
// no retry, and on CI on every run, since nothing caches ~/.m2 there. A dropped connection then
// fails the job before a test runs (#202). Here Gradle resolves the jar instead, as a build
// dependency: with retries, into the Gradle user home CI caches. The tests run offline, pointed at
// a directory the build fills with it.
//
// The jar is Robolectric's own choice for the SDK a test asks for (`sdk=35` in each module's
// robolectric.properties), so the catalog pins the exact build its release names. A Robolectric
// update that moves it fails its host tests with "Unable to locate dependency" and the file name
// it wants: put that version in the catalog (`robolectric-androidAll`). A test that asks for
// another SDK needs that SDK's jar added to the configuration.

private val robolectricFrameworkJars: Configuration =
    configurations.create("robolectricFrameworkJars") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies.add(
    robolectricFrameworkJars.name,
    catalogLibrary("robolectric-android-all-instrumented"),
)

private val syncRobolectricFrameworkJars =
    tasks.register<SyncRobolectricFrameworkJars>("syncRobolectricFrameworkJars") {
        jars.from(robolectricFrameworkJars)
        outputDirectory.set(layout.buildDirectory.dir("robolectric-framework-jars"))
    }

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(
        RobolectricOfflineArguments(
            directory = syncRobolectricFrameworkJars.flatMap { it.outputDirectory }
        )
    )
}

// Robolectric brings Guava onto the host-test classpaths (4.17: 33.6.0-jre, GHSA-xxph-c9ww-hj94),
// where the build-tool floors do not reach: those cover the classpaths the plugins and their tools
// resolve, and a test classpath is no place for the other floors. The Guava floor alone applies
// here, on the scope the tests declare Robolectric in, so the compile and runtime classpaths and
// the Kotlin Gradle plugin's metadata resolution, which holds to the compile classpath's versions,
// move together. A constraint only ever raises a version. Unneeded once a Robolectric release
// brings the floor's version; when the floor itself goes, this lookup fails and says so.
private val guavaFloor = catalogLibrary("buildToolFloor-guava")
private val hostTestConstraints = dependencies.constraints

configurations
    .matching { it.name == "androidHostTestImplementation" || it.name == "testImplementation" }
    .configureEach { hostTestConstraints.add(name, guavaFloor) }
