// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    id("supabase-offline.kmp-library")
    id("supabase-offline.published")
}

kotlin {
    android {
        namespace = "io.github.nesfeder.supabase.offline.testing"

        // Runs commonTest on the JVM for the Android target.
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.supabaseOfflineKt)
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
