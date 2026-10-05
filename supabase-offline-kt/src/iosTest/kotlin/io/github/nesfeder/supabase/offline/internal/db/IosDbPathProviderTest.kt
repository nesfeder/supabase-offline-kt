// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The iOS database file is `supabase_offline.db` directly in the app's `Library/Application
 * Support` directory, where installs keep their cache and their unsent writes; moving or renaming
 * it would orphan that file. A database found directly in `Documents`, an earlier default, is moved
 * there when a client opens (`EarlierDefaultLocationIosTest`).
 */
class IosDbPathProviderTest {

    @Test
    fun dbPath_is_supabase_offline_db_in_the_application_support_directory() {
        // Given — the Application Support directory, resolved independently of the provider
        val applicationSupport =
            NSSearchPathForDirectoriesInDomains(
                    NSApplicationSupportDirectory,
                    NSUserDomainMask,
                    true,
                )
                .first() as String

        // When
        val path = defaultDatabasePath()

        // Then
        assertEquals("$applicationSupport/supabase_offline.db", path.toString())
    }

    @Test
    fun the_earlier_default_is_supabase_offline_db_in_the_documents_directory() {
        // Given — the Documents directory, resolved independently of the provider
        val documents =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first()
                as String

        // When
        val location = dataLocationInDocuments()

        // Then — where the database and its files are looked for, to be moved
        assertEquals("$documents/supabase_offline.db", location.databasePath.toString())
        assertEquals("$documents/supabase_offline", location.dataDirectory.toString())
    }
}
