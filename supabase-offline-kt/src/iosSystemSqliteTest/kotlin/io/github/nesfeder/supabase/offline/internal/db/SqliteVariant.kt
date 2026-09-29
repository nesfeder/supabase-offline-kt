package io.github.nesfeder.supabase.offline.internal.db

/**
 * What the iOS tests expect of the build they run in: this is the system-SQLite build
 * (`-Psupabase.offline.iosSqlite=system`), which links the operating system's libsqlite3 and not
 * the bundled library.
 */
internal object SqliteVariant {

    /** The build's name, as `-Psupabase.offline.iosSqlite` spells it. */
    const val NAME: String = "system"

    /** A database last written by the other build's SQLite, for this build to open in place. */
    val otherDriverDatabase: CrossDriverDatabase = BUNDLED_WRITTEN_DATABASE

    /**
     * Whether the SQLite behind the default driver is the one this build is meant to link. Apple
     * builds its SQLite without extension loading; the bundled one has it. A build that silently
     * picked up the bundled library would fail here.
     *
     * @param compileOptions what `PRAGMA compile_options` lists.
     * @return `true` for Apple's system SQLite.
     */
    fun isExpectedLibrary(compileOptions: Set<String>): Boolean = "OMIT_LOAD_EXTENSION" in compileOptions
}
