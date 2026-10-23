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
 * The mutation queue's projected and counting queries on a real Android database file. See
 * [MutationQueueProjectionsScenario].
 */
@RunWith(AndroidJUnit4::class)
class MutationQueueProjectionsAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "queue-projections")
    private val databasePath = File(directory, "supabase_offline.db").absolutePath
    private val scenario = MutationQueueProjectionsScenario { path ->
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
    fun the_projections_read_exactly_the_rows_they_stand_for() = runTest {
        scenario.theProjectionsReadExactlyTheRowsTheyStandFor(databasePath)
    }
}
