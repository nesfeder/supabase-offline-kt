package io.github.nesfeder.supabase.offline.internal.storage

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Downloaded storage objects stay under `Documents/supabase_offline` on iOS, where existing
 * installs keep them and where `clearAllCache` looks for them. Moving the directory would orphan
 * the files, and they would then survive sign-out.
 */
class PlatformBaseDirectoryIosTest {

    @Test
    fun path_is_supabase_offline_in_the_documents_directory() {
        // Given — the Documents directory, resolved independently of the implementation
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

        // When
        val path = PlatformBaseDirectory().path

        // Then
        assertEquals("$documents/supabase_offline", path.toString())
    }
}
