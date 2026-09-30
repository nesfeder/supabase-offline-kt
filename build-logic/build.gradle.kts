// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
plugins { `kotlin-dsl` }

// compileOnly: the root build loads these plugins once (`apply false`), and the modules or the
// convention plugins apply them from there, so the build never holds two versions of one.
dependencies {
    compileOnly(libs.android.gradleApi)
    compileOnly(libs.androidx.room.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
}
