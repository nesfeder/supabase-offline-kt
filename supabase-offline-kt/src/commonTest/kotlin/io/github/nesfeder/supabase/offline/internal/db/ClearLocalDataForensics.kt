// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.sqlite.execSQL
import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.ktor.http.HttpStatusCode
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Where a cleared user's data could still be read from the raw database files, looked for while the
 * client is open and again after it closes. Every marker field is `false` on a clean clear.
 *
 * @property reservedRowsBeforeClear the mutation queue's reserved rows the clear had to remove.
 * @property reservedRowsAfterClear those left once the client closed.
 */
internal data class ClearLocalDataForensics(
    val inDatabaseWhileOpen: Boolean,
    val inWalWhileOpen: Boolean,
    val inShmWhileOpen: Boolean,
    val inDatabaseAfterClose: Boolean,
    val inWalAfterClose: Boolean,
    val inShmAfterClose: Boolean,
    val reservedRowsBeforeClear: Long,
    val reservedRowsAfterClear: Long,
) {
    /** Whether the marker was found anywhere. */
    val anyRecoverable: Boolean =
        inDatabaseWhileOpen ||
            inWalWhileOpen ||
            inShmWhileOpen ||
            inDatabaseAfterClose ||
            inWalAfterClose ||
            inShmAfterClose
}

/**
 * Opens a client in [directory] with [openClient] — the platform's open path — and fills it the way
 * an app does, every piece carrying one marker: 40 cached SELECT payloads, a write the server
 * rejected (on the mutation queue's persisted rejected list), and a keyed INSERT queued offline
 * (pending, with its owner and unconfirmed-send records). Then clears local data with a reactive
 * read live, and searches the database, `-wal` and `-shm` for the marker, open and after close.
 *
 * @param directory the client's data directory; the database is created in it.
 * @param openClient the platform's open path.
 * @return what was found where.
 */
internal suspend fun runClearLocalDataForensics(
    directory: Path,
    openClient: (SupabaseClient, Offline.Config) -> Offline,
): ClearLocalDataForensics {
    val marker = "MARKER${Random.nextLong().toULong().toString(16)}"
    val padding = "lorem ".repeat(60)
    var phase = Phase.ONLINE
    val server =
        RecordingPostgrestServer(
            answer = { request ->
                when (phase) {
                    Phase.ONLINE -> {
                        val id = request.query["id"]?.removePrefix("eq.") ?: "0"
                        okJson(
                            """[{"id":$id,"email":"$marker-$id@example.com","bio":"$padding"}]"""
                        )
                    }
                    Phase.OFFLINE -> connectivityFailure()
                    Phase.REJECTING ->
                        postgrestError(
                            HttpStatusCode.BadRequest,
                            code = "23514",
                            message = "check failed for $marker",
                        )
                }
            }
        )
    val client =
        openClient(
            server.supabaseClient(),
            Offline.createConfig { dataDirectory = directory.toString() },
        )
    val databasePath = directory / OFFLINE_DATABASE_FILE_NAME
    val wal = "$databasePath-wal".toPath()
    val shm = "$databasePath-shm".toPath()
    try {
        val profiles = client.from("profiles")
        repeat(CACHED_PAYLOADS) { index -> profiles.select { filter { eq("id", index) } } }
        phase = Phase.OFFLINE
        profiles.insert(
            buildJsonArray { add(buildJsonObject { put("email", "$marker-rejected@example.com") }) }
        )
        phase = Phase.REJECTING
        client.syncPendingMutations().syncedCount
        phase = Phase.OFFLINE
        profiles.insert(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", 77)
                        put("email", "$marker@example.com")
                    }
                )
            }
        )
        check((client as OfflineClient).rejectedMutationList().size == 1) {
            "the rejection was not recorded"
        }
        check(client.pendingMutationCount() == 1) { "the offline write was not queued" }
        check(containsMarker(databasePath, marker) || containsMarker(wal, marker)) {
            "the marker never reached the database"
        }
        val reservedBefore = reservedRowCount(databasePath)

        // A screen still observing the table while it is cleared, as an app's would be: its reads
        // hold the database open while the clear rewrites it
        coroutineScope {
            val observer =
                launch(Dispatchers.Default) { runCatching { profiles.selectAsFlow().collect {} } }
            client.clearLocalData()
            observer.cancelAndJoin()
        }

        val whileOpen =
            Triple(
                containsMarker(databasePath, marker),
                containsMarker(wal, marker),
                containsMarker(shm, marker),
            )
        client.close()
        return ClearLocalDataForensics(
            inDatabaseWhileOpen = whileOpen.first,
            inWalWhileOpen = whileOpen.second,
            inShmWhileOpen = whileOpen.third,
            inDatabaseAfterClose = containsMarker(databasePath, marker),
            inWalAfterClose = containsMarker(wal, marker),
            inShmAfterClose = containsMarker(shm, marker),
            reservedRowsBeforeClear = reservedBefore,
            reservedRowsAfterClear = reservedRowCount(databasePath),
        )
    } finally {
        client.close()
    }
}

/**
 * What a clear did while a read on another connection held the database's write-ahead log.
 *
 * @property clearTook how long `clearLocalData()` took.
 * @property heldReadKeptTheMarker whether the marker could still be read from the files while the
 *   read was held: `true` shows the read did hold the log, so the rest of the report means
 *   something.
 * @property cleanAfterTheReadEnded whether, once the read ended and with the client still open, the
 *   marker left the files and the write-ahead log was emptied, within the wait.
 */
internal data class ClearUnderAHeldReadReport(
    val clearTook: Duration,
    val heldReadKeptTheMarker: Boolean,
    val cleanAfterTheReadEnded: Boolean,
)

/**
 * Opens a client in [directory] with [openClient], caches rows carrying one marker, then opens a
 * second connection to the database file and holds a read on it across `clearLocalData()`, as
 * another reader of the file would. Once the clear has returned it lets the read go, and waits up
 * to [settle] for the marker to leave the files with the client still open.
 *
 * @param directory the client's data directory; the database is created in it.
 * @param openClient the platform's open path.
 * @return what the clear did.
 */
internal suspend fun runClearUnderAHeldRead(
    directory: Path,
    settle: Duration = 15.seconds,
    openClient: (SupabaseClient, Offline.Config) -> Offline,
): ClearUnderAHeldReadReport {
    val marker = "MARKER${Random.nextLong().toULong().toString(16)}"
    val padding = "lorem ".repeat(60)
    val server =
        RecordingPostgrestServer(
            answer = { request ->
                val id = request.query["id"]?.removePrefix("eq.") ?: "0"
                okJson("""[{"id":$id,"email":"$marker-$id@example.com","bio":"$padding"}]""")
            }
        )
    val client =
        openClient(
            server.supabaseClient(),
            Offline.createConfig { dataDirectory = directory.toString() },
        )
    val databasePath = directory / OFFLINE_DATABASE_FILE_NAME
    val wal = "$databasePath-wal".toPath()
    try {
        val profiles = client.from("profiles")
        repeat(CACHED_PAYLOADS) { index -> profiles.select { filter { eq("id", index) } } }
        check(containsMarker(databasePath, marker) || containsMarker(wal, marker)) {
            "the marker never reached the database"
        }

        // A read on its own connection, holding the write-ahead log until it is let go
        val reader = defaultSQLiteDriver().open(databasePath.toString())
        val clearTook: Duration
        val heldReadKeptTheMarker: Boolean
        try {
            reader.execSQL("BEGIN")
            val statement = reader.prepare("SELECT COUNT(*) FROM object_cache")
            try {
                check(statement.step())
            } finally {
                statement.close()
            }
            clearTook = measureTime { client.clearLocalData() }
            heldReadKeptTheMarker =
                containsMarker(databasePath, marker) || containsMarker(wal, marker)
            reader.execSQL("COMMIT")
        } finally {
            reader.close()
        }

        val clean =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(settle) {
                    while (
                        containsMarker(databasePath, marker) ||
                            containsMarker(wal, marker) ||
                            (FileSystem.SYSTEM.metadataOrNull(wal)?.size ?: 0L) > 0L
                    ) {
                        delay(POLL)
                    }
                    true
                }
            }
        return ClearUnderAHeldReadReport(
            clearTook = clearTook,
            heldReadKeptTheMarker = heldReadKeptTheMarker,
            cleanAfterTheReadEnded = clean == true,
        )
    } finally {
        client.close()
    }
}

/** How often the files are searched again while the log empties. */
private val POLL: Duration = 50.milliseconds

/** What the forensics server answers. */
private enum class Phase {
    ONLINE,
    OFFLINE,
    REJECTING,
}

private const val CACHED_PAYLOADS = 40

/**
 * Counts the mutation queue's reserved rows through a second connection, which waits out the
 * client's own writes rather than failing on them.
 */
private fun reservedRowCount(databasePath: Path): Long {
    val connection = defaultSQLiteDriver().open(databasePath.toString())
    try {
        connection.execSQL("PRAGMA busy_timeout = 5000")
        val statement = connection.prepare("SELECT COUNT(*) FROM object_cache WHERE table_name = ?")
        try {
            statement.bindText(1, RESERVED_TABLE_NAME)
            check(statement.step())
            return statement.getLong(0)
        } finally {
            statement.close()
        }
    } finally {
        connection.close()
    }
}

private fun containsMarker(file: Path, marker: String): Boolean {
    if (!FileSystem.SYSTEM.exists(file)) return false
    return FileSystem.SYSTEM.read(file) { readByteString() }.indexOf(marker.encodeUtf8()) >= 0
}
