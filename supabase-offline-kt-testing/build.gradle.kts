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
            // To look at the device's disk, which the in-memory client must leave untouched.
            implementation(libs.okio)
        }
    }
}

// The factories and the in-memory client call into the library outside its API records, so they
// work with the library of their own version only. A strict constraint in the published metadata
// makes an app that resolves another version of the library beside them fail resolution, rather
// than fail a test with a missing method.
dependencies {
    constraints {
        add("commonMainApi", "${project.group}:supabase-offline-kt") {
            version { strictly(project.version.toString()) }
            because("the testing artifact calls into the library outside its API records")
        }
    }
}
