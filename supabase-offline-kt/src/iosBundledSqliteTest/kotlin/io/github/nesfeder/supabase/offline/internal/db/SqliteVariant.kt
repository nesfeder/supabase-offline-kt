// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

/**
 * What the iOS tests expect of the build they run in: this is the bundled-SQLite build, the default
 * and the one every published artifact is made from.
 */
internal object SqliteVariant {

    /** The build's name, as `-Psupabase.offline.iosSqlite` spells it. */
    const val NAME: String = "bundled"

    /** A database last written by the other build's SQLite, for this build to open in place. */
    val otherDriverDatabase: CrossDriverDatabase = SYSTEM_WRITTEN_DATABASE

    /**
     * Whether the SQLite behind the default driver is the one this build is meant to link. The
     * bundled SQLite is compiled with extension loading; Apple's system SQLite omits it.
     *
     * @param compileOptions what `PRAGMA compile_options` lists.
     * @return `true` for the bundled SQLite.
     */
    fun isExpectedLibrary(compileOptions: Set<String>): Boolean =
        "OMIT_LOAD_EXTENSION" !in compileOptions
}
