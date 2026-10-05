// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.db.buildOfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.offlineDatabaseBuilder
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.closingAfter
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The default iOS location is `Library/Application Support`. Data an earlier default kept in
 * `Documents`, where an app with file sharing on shows it in the Files app, moves there when a
 * client opens: the database with its unsent writes, and the file cache with it.
 *
 * These cases use the simulator's real `Documents` and `Application Support` directories, and empty
 * the plugin's files from both before and after each one.
 */
class EarlierDefaultLocationIosTest {

    private val documents: Path = userDirectory(NSDocumentDirectory)
    private val applicationSupport: Path = userDirectory(NSApplicationSupportDirectory)
    private val elsewhere: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-earlier-default-${Random.nextLong().toULong()}"

    @BeforeTest
    fun setUp() {
        removePluginFiles()
    }

    @AfterTest
    fun tearDown() {
        removePluginFiles()
        FileSystem.SYSTEM.deleteRecursively(elsewhere, mustExist = false)
    }

    private fun userDirectory(directory: NSSearchPathDirectory): Path =
        (NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).first() as String)
            .toPath()

    private fun removePluginFiles() {
        listOf(documents, applicationSupport).forEach { directory ->
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                FileSystem.SYSTEM.delete(
                    directory / "supabase_offline.db$suffix",
                    mustExist = false,
                )
            }
            FileSystem.SYSTEM.deleteRecursively(directory / "supabase_offline", mustExist = false)
        }
    }

    /**
     * A client on the database in `Documents`, with its files there too, as the earlier default.
     */
    private fun openInDocuments(server: RecordingPostgrestServer): OfflineClient =
        openOfflineClient(
            supabase = server.supabaseClient(),
            config = Offline.Config(),
            databasePath = documents / "supabase_offline.db",
            dataDirectory = documents / "supabase_offline",
            openDatabase = { databasePath ->
                buildOfflineDatabase(offlineDatabaseBuilder(databasePath.toString()))
            },
        )

    /**
     * Queues one write offline in the database in `Documents`, and leaves a cached file beside it.
     */
    private suspend fun leaveAnUnsentWriteAndAFileInDocuments() {
        openInDocuments(RecordingPostgrestServer(answer = { connectivityFailure() }))
            .closingAfter { client ->
                client
                    .from("todos")
                    .insert(buildJsonArray { add(buildJsonObject { put("title", "unsent") }) })
                assertEquals(1, client.pendingMutationCount())
            }
        val cachedFiles = documents / "supabase_offline" / "storage" / "photos"
        FileSystem.SYSTEM.createDirectories(cachedFiles)
        FileSystem.SYSTEM.write(cachedFiles / "a.jpg") { writeUtf8("bytes") }
    }

    @Test
    fun a_database_in_documents_moves_to_application_support_with_its_unsent_writes() = runTest {
        // Given — an install whose earlier default kept a queued write and a file in Documents
        leaveAnUnsentWriteAndAFileInDocuments()

        // When — a client opens at the default location
        openPlatformClient(newTestSupabaseClient(), Offline.createConfig {}).closingAfter { client
            ->
            // Then — the queue came along, the file cache with it, and Documents holds none of it
            assertEquals(1, client.pendingMutationCount())
            assertTrue(FileSystem.SYSTEM.exists(applicationSupport / "supabase_offline.db"))
            assertEquals(
                "bytes",
                FileSystem.SYSTEM.read(
                    applicationSupport / "supabase_offline" / "storage" / "photos" / "a.jpg"
                ) {
                    readUtf8()
                },
            )
            assertFalse(FileSystem.SYSTEM.exists(documents / "supabase_offline.db"))
            assertFalse(FileSystem.SYSTEM.exists(documents / "supabase_offline.db-wal"))
            assertFalse(FileSystem.SYSTEM.exists(documents / "supabase_offline"))
        }
    }

    @Test
    fun a_fresh_install_opens_in_application_support() = runTest {
        // Given — nothing of the plugin on the device

        // When
        openPlatformClient(newTestSupabaseClient(), Offline.createConfig {}).closingAfter { client
            ->
            // The first query opens the database.
            assertEquals(0, client.pendingMutationCount())

            // Then
            assertTrue(FileSystem.SYSTEM.exists(applicationSupport / "supabase_offline.db"))
            assertTrue(FileSystem.SYSTEM.exists(applicationSupport / "supabase_offline"))
            assertFalse(FileSystem.SYSTEM.exists(documents / "supabase_offline.db"))
            assertFalse(FileSystem.SYSTEM.exists(documents / "supabase_offline"))
        }
    }

    @Test
    fun a_database_in_documents_that_another_client_holds_is_not_moved() = runTest {
        // Given — a client open on the database in Documents
        leaveAnUnsentWriteAndAFileInDocuments()
        openInDocuments(RecordingPostgrestServer(answer = { connectivityFailure() })).closingAfter {
            // When — a client opens at the default location meanwhile
            val failure =
                assertFailsWith<IllegalStateException> {
                    openPlatformClient(newTestSupabaseClient(), Offline.createConfig {})
                }

            // Then — it is told the database is in use, and nothing moved
            assertTrue(
                "already open" in failure.message.orEmpty(),
                "unclear message: ${failure.message}",
            )
            assertTrue(FileSystem.SYSTEM.exists(documents / "supabase_offline.db"))
            assertTrue(FileSystem.SYSTEM.exists(documents / "supabase_offline"))
            assertFalse(FileSystem.SYSTEM.exists(applicationSupport / "supabase_offline.db"))
        }
    }

    @Test
    fun with_a_database_in_both_places_the_one_in_documents_is_left_and_reported() = runTest {
        // Given — a database at the default location, and another with a queued write in Documents
        openPlatformClient(newTestSupabaseClient(), Offline.createConfig {}).closingAfter { client
            ->
            client.pendingMutationCount()
        }
        leaveAnUnsentWriteAndAFileInDocuments()
        val logs = RecordingLogSink()

        // When
        openPlatformClient(newTestSupabaseClient(logs = logs), Offline.createConfig {})
            .closingAfter { client ->
                // Then — the default one is used, the other is untouched, and the app is told
                assertEquals(0, client.pendingMutationCount())
                assertTrue(FileSystem.SYSTEM.exists(documents / "supabase_offline.db"))
                assertTrue(
                    logs.entriesAt(EngineLogLevel.WARNING).isNotEmpty(),
                    "the left-behind database was not reported",
                )
            }
    }

    @Test
    fun a_configured_directory_takes_a_database_found_in_documents() = runTest {
        // Given
        leaveAnUnsentWriteAndAFileInDocuments()

        // When — the app sets a data directory
        openPlatformClient(
                newTestSupabaseClient(),
                Offline.createConfig { dataDirectory = elsewhere.toString() },
            )
            .closingAfter { client ->
                // Then — the queue reached the configured directory, through the default location
                assertEquals(1, client.pendingMutationCount())
                assertTrue(FileSystem.SYSTEM.exists(elsewhere / "supabase_offline.db"))
                assertFalse(FileSystem.SYSTEM.exists(documents / "supabase_offline.db"))
                assertFalse(FileSystem.SYSTEM.exists(applicationSupport / "supabase_offline.db"))
            }
    }
}
