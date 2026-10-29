// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import io.github.nesfeder.supabase.offline.buildlogic.constrainToBuildToolFloors

// supabase-offline.build-tool-floors: the build-tool floors on the tool configurations plugins
// create in a module: the Android Gradle plugin's lint, the Kotlin Gradle plugin's Swift export and
// its publishing validation (created when a module signs its publications), Dokka's generator and
// its publication plugins (the root project's aggregation of the API reference resolves those with
// the all-modules page plugin, which brings Dokka's base plugin and its libraries), every
// configuration of the Android Gradle plugin's unified test platform, which an application module
// creates to run instrumented tests and names `unified-test-platform-…`, and every configuration of
// the Kotlin Symbol Processing plugin, named `ksp…`: the processor classpaths that resolve Room's
// compiler for each target, and its own plugin classpaths. Every role plugin applies it, so no
// module can miss them; none of them is published or packaged.

constrainToBuildToolFloors(
    setOf(
        "androidLintTool",
        "swiftExportClasspath",
        "kotlinBouncyCastleConfiguration",
        "dokkaHtmlGeneratorRuntime",
        "dokkaHtmlPublicationPlugin",
    ),
    configurationNamePrefixes = setOf("unified-test-platform-", "ksp"),
)
