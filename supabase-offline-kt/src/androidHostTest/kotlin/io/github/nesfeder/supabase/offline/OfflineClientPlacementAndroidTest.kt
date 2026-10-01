// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:Suppress("DEPRECATION")

package io.github.nesfeder.supabase.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.internal.db.buildOfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.offlineDatabaseBuilder
import io.github.nesfeder.supabase.offline.internal.openOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.closingAfter
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.SYSTEM
import org.junit.runner.RunWith

/**
 * Where the Android client keeps its data, and which clients may share it: one client per database
 * and per data directory, however the path is spelled, and an app that starts setting
 * `offlineStoragePath` takes its unsent writes along instead of stranding them in the default
 * database.
 */
@RunWith(AndroidJUnit4::class)
class OfflineClientPlacementAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root: Path =
        File(context.cacheDir, "offline-placement-${Random.nextLong().toULong()}").toOkioPath()
    private val defaultDatabase: File = context.getDatabasePath("supabase_offline.db")

    @BeforeTest
    fun setUp() {
        // Every case decides for itself whether a database waits at the default path.
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File("${defaultDatabase.path}$suffix").delete()
        }
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }

    private fun configIn(directory: Path, logger: OfflineLogger? = null) =
        SupabaseOfflineConfig(offlineStoragePath = directory, logger = logger)

    /** Opens a client with [config] and queues one write in it while offline, then closes it. */
    private suspend fun queueOneWriteOffline(
        config: SupabaseOfflineConfig = SupabaseOfflineConfig()
    ) {
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = createSupabaseOfflineClient(context, server.supabaseClient(), config)
        try {
            client
                .from("todos")
                .insert(buildJsonArray { add(buildJsonObject { put("title", "unsent") }) })
            assertEquals(1, client.pendingMutationCount())
        } finally {
            client.close()
        }
    }

    @Test
    fun a_configured_directory_adopts_the_default_databases_unsent_writes() = runTest {
        // Given — an install that queued a write in the default database
        queueOneWriteOffline()
        val configured = root / "configured"

        // When — the app starts setting offlineStoragePath
        val client =
            createSupabaseOfflineClient(context, newTestSupabaseClient(), configIn(configured))

        // Then — the queue came along, and nothing is left behind at the default path
        try {
            assertEquals(1, client.pendingMutationCount())
            assertTrue(FileSystem.SYSTEM.exists(configured / "supabase_offline.db"))
            assertFalse(defaultDatabase.exists(), "the default database was left behind")
            assertFalse(File("${defaultDatabase.path}-wal").exists())
        } finally {
            client.close()
        }
    }

    @Test
    fun a_configured_directory_with_its_own_database_keeps_it_and_leaves_the_default_alone() =
        runTest {
            // Given — both locations hold a database; only the default one has an unsent write
            val configured = root / "configured"
            createSupabaseOfflineClient(context, newTestSupabaseClient(), configIn(configured))
                .closingAfter { client ->
                    // Room creates the file on the first query.
                    client.pendingMutationCount()
                }
            assertTrue(FileSystem.SYSTEM.exists(configured / "supabase_offline.db"))
            queueOneWriteOffline()
            val logger = RecordingOfflineLogger()

            // When
            val client =
                createSupabaseOfflineClient(
                    context,
                    newTestSupabaseClient(),
                    configIn(configured, logger),
                )

            // Then — the configured database is used, the default one is untouched, and the app is
            // told
            try {
                assertEquals(0, client.pendingMutationCount())
                assertTrue(defaultDatabase.exists())
                assertTrue(
                    logger.entriesAt(OfflineLogLevel.WARNING).isNotEmpty(),
                    "the left-behind database was not reported",
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun one_directory_reached_through_a_symlink_is_still_one_database() = runTest {
        // Given — the same directory under two spellings
        val real = root / "real"
        val link = root / "link"
        FileSystem.SYSTEM.createDirectories(real)
        FileSystem.SYSTEM.createSymlink(link, real)
        val first = createSupabaseOfflineClient(context, newTestSupabaseClient(), configIn(link))

        // When
        val second = runCatching {
            createSupabaseOfflineClient(context, newTestSupabaseClient(), configIn(real))
        }

        // Then
        second.getOrNull()?.close()
        first.close()
        assertIs<IllegalStateException>(
            second.exceptionOrNull(),
            "two clients opened one database through a symlink",
        )
    }

    @Test
    fun two_clients_never_share_a_data_directory() = runTest {
        // Given — a client whose files live in a directory
        val data = root / "data"
        val open = { databasePath: Path ->
            buildOfflineDatabase(offlineDatabaseBuilder(context, databasePath.toString()))
        }
        val first =
            openOfflineClient(
                newTestSupabaseClient(),
                Offline.Config(),
                root / "a" / "supabase_offline.db",
                data,
                open,
            )

        // When — another client, on another database, asks for the same data directory
        val second = runCatching {
            openOfflineClient(
                newTestSupabaseClient(),
                Offline.Config(),
                root / "b" / "supabase_offline.db",
                data,
                open,
            )
        }

        // Then — its sweep and its clear would reach the first client's files
        second.getOrNull()?.close()
        first.close()
        assertIs<IllegalStateException>(
            second.exceptionOrNull(),
            "two clients were given one data directory",
        )
    }
}
