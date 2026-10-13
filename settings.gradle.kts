// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
pluginManagement {
    // The convention plugins (supabase-offline.*) the modules apply by id.
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// Not the name of any module, so type-safe project accessors (projects.supabaseOfflineKt) can be
// generated. Nothing published carries it: each published module's group comes from GROUP.
rootProject.name = "supabase-offline-kt-root"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Each module applies the convention plugins that fit its role (build-logic/src/main/kotlin); a
// published module also names its artifact in its own gradle.properties.
include(":supabase-offline-kt")

include(":supabase-offline-kt-testing")

// The Kotlin side of the Swift package: an iOS framework, SupabaseOfflineCore, that the package
// wraps. Never published, and outside the API records: its Objective-C header is its contract.
include(":supabase-offline-swift-bridge")

// The Android sample app, Offline Tasks. Built on every run that touches a build input, so a change
// to the library that breaks it fails CI; it is never published.
include(":samples:android")

// The sample takes the library from Maven Central, as an app does. -PsupabaseOffline.local=true
// substitutes this checkout's two modules for both published artifacts, the testing artifact's own
// dependency on the library included; CI passes it, so a change to the library reaches the sample.
if (providers.gradleProperty("supabaseOffline.local").orNull == "true") {
    gradle.lifecycle.beforeProject {
        if (path == ":samples:android") {
            configurations.configureEach {
                resolutionStrategy.dependencySubstitution {
                    // Each published artifact has the name of the module that builds it.
                    for (artifact in listOf("supabase-offline-kt", "supabase-offline-kt-testing")) {
                        substitute(module("io.github.nesfeder.supabase-offline:$artifact"))
                            .using(project(":$artifact"))
                    }
                }
            }
        }
    }
}
