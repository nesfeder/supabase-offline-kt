// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * A queued write whose stored operation this build does not write, on a real Android database file:
 * it is refused alone, and the table's reads, the pending lists and the rest of the queue go on.
 * See [UnknownOperationRowScenario].
 */
@RunWith(AndroidJUnit4::class)
class UnknownOperationRowAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "unknown-operation")
    private val databasePath = File(directory, "supabase_offline.db").absolutePath
    private val scenario = UnknownOperationRowScenario { path ->
        buildOfflineDatabase(offlineDatabaseBuilder(context, path))
    }

    @BeforeTest
    fun setUp() {
        directory.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun an_unknown_operation_is_refused_alone_and_the_rest_goes_on() = runTest {
        scenario.anUnknownOperationIsRefusedAloneAndTheRestGoesOn(databasePath)
    }
}
