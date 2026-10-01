// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import io.github.nesfeder.supabase.offline.buildlogic.constrainToBuildToolFloors

// supabase-offline.build-tool-floors: the build-tool floors on the tool configurations plugins
// create in a module: the Android Gradle plugin's lint, the Kotlin Gradle plugin's Swift export and
// its publishing validation (created when a module signs its publications), Dokka's generator, and
// every configuration of the Android Gradle plugin's unified test platform, which an application
// module creates to run instrumented tests and names `unified-test-platform-…`. Every role plugin
// applies it, so no module can miss them; none of them is published or packaged.

constrainToBuildToolFloors(
    setOf(
        "androidLintTool",
        "swiftExportClasspath",
        "kotlinBouncyCastleConfiguration",
        "dokkaHtmlGeneratorRuntime",
    ),
    configurationNamePrefixes = setOf("unified-test-platform-"),
)
