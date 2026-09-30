// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import io.github.nesfeder.supabase.offline.buildlogic.constrainToBuildToolFloors

// supabase-offline.build-tool-floors: the build-tool floors on the tool configurations plugins
// create in a module, the Android Gradle plugin's lint and the Kotlin Gradle plugin's Swift export.
// Every role plugin applies it, so no module can miss them; neither configuration is published.

constrainToBuildToolFloors(setOf("androidLintTool", "swiftExportClasspath"))
