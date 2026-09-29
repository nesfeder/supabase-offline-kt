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

include(":supabase-offline-kt")

include(":supabase-offline-kt-testing")
