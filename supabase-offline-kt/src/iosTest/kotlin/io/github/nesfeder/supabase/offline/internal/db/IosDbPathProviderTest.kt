package io.github.nesfeder.supabase.offline.internal.db

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS database file stays `supabase_offline.db` directly in the app's Documents directory,
 * where existing installs keep their cache and their unsent writes. Moving or renaming it would
 * orphan that file.
 */
class IosDbPathProviderTest {

    @Test
    fun dbPath_is_supabase_offline_db_in_the_documents_directory() {
        // Given — the Documents directory, resolved independently of the provider
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

        // When
        val path = IosDbPathProvider().dbPath()

        // Then
        assertEquals("$documents/supabase_offline.db", path.toString())
    }
}
