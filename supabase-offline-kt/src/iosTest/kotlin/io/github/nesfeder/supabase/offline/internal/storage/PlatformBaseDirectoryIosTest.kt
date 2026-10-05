// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Downloaded storage objects stay under `Library/Application Support/supabase_offline` on iOS,
 * where installs keep them and where `clearLocalData` looks for them. Moving the directory would
 * orphan the files, and they would then survive sign-out.
 */
class PlatformBaseDirectoryIosTest {

    @Test
    fun path_is_supabase_offline_in_the_application_support_directory() {
        // Given — the Application Support directory, resolved independently of the implementation
        val applicationSupport =
            NSSearchPathForDirectoriesInDomains(
                    NSApplicationSupportDirectory,
                    NSUserDomainMask,
                    true,
                )
                .first() as String

        // When
        val path = PlatformBaseDirectory().path

        // Then
        assertEquals("$applicationSupport/supabase_offline", path.toString())
    }
}
