// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
plugins { `kotlin-dsl` }

// compileOnly: the root build loads these plugins once (`apply false`), and the modules or the
// convention plugins apply them from there, so the build never holds two versions of one.
dependencies {
    compileOnly(libs.android.gradleApi)
    compileOnly(libs.androidx.room.gradlePlugin)
    compileOnly(libs.dokka.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
    compileOnly(libs.mavenPublish.gradlePlugin)

    // The build-tool floors (gradle/libs.versions.toml, `buildToolFloor-*`) for the plugins' full
    // runtime, which kotlin-dsl resolves to generate accessors. Constraints never lower a version.
    val catalog = versionCatalogs.named("libs")
    catalog.libraryAliases
        .filter { it.startsWith("buildToolFloor.") }
        .forEach {
            constraints.add(
                "precompiledScriptPluginAccessorsGenerationClasspath",
                catalog.findLibrary(it).get(),
            )
        }
}
