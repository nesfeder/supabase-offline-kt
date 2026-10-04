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
 * A queued write whose stored operation this build does not write, on a real iOS database file: it
 * is refused alone, and the table's reads, the pending lists and the rest of the queue go on. See
 * [UnknownOperationRowScenario].
 */
class UnknownOperationRowIosTest {

    private val directory =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-unknown-operation-${Random.nextLong()}"
    private val databasePath = (directory / "supabase_offline.db").toString()
    private val scenario = UnknownOperationRowScenario { path ->
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
    fun an_unknown_operation_is_refused_alone_and_the_rest_goes_on() = runTest {
        scenario.anUnknownOperationIsRefusedAloneAndTheRestGoesOn(databasePath)
    }
}
