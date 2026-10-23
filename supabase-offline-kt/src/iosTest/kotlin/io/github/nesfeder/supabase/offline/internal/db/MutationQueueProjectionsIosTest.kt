// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM

/**
 * The mutation queue's projected and counting queries on a real iOS database file. See
 * [MutationQueueProjectionsScenario].
 */
class MutationQueueProjectionsIosTest {

    private val directory =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-queue-projections-${Random.nextLong()}"
    private val databasePath = (directory / "supabase_offline.db").toString()
    private val scenario = MutationQueueProjectionsScenario { path ->
        buildOfflineDatabase(offlineDatabaseBuilder(path))
    }

    @BeforeTest
    fun setUp() {
        FileSystem.SYSTEM.createDirectories(directory)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun the_projections_read_exactly_the_rows_they_stand_for() = runTest {
        scenario.theProjectionsReadExactlyTheRowsTheyStandFor(databasePath)
    }
}
