// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The iOS database file stays `supabase_offline.db` directly in the app's Documents directory,
 * where existing installs keep their cache and their unsent writes. Moving or renaming it would
 * orphan that file.
 */
class IosDbPathProviderTest {

    @Test
    fun dbPath_is_supabase_offline_db_in_the_documents_directory() {
        // Given — the Documents directory, resolved independently of the provider
        val documents =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first()
                as String

        // When
        val path = defaultDatabasePath()

        // Then
        assertEquals("$documents/supabase_offline.db", path.toString())
    }
}
