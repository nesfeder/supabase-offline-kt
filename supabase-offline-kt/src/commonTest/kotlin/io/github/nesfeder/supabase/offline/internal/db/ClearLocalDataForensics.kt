package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random

/**
 * Where a cleared user's data could still be read from the raw database files, looked for while
 * the client is open and again after it closes. Every field is `false` on a clean clear.
 */
internal data class ClearLocalDataForensics(
    val inDatabaseWhileOpen: Boolean,
    val inWalWhileOpen: Boolean,
    val inShmWhileOpen: Boolean,
    val inDatabaseAfterClose: Boolean,
    val inWalAfterClose: Boolean,
    val inShmAfterClose: Boolean,
) {
    /** Whether the marker was found anywhere. */
    val anyRecoverable: Boolean =
        inDatabaseWhileOpen || inWalWhileOpen || inShmWhileOpen || inDatabaseAfterClose || inWalAfterClose || inShmAfterClose
}

/**
 * Opens a client in [directory] with [openClient] — the platform factory — and fills it the way an
 * app does: 40 cached SELECT payloads and a queued offline write, each carrying a unique marker.
 * Then clears local data, with a reactive read of the table live, and searches the database,
 * `-wal` and `-shm` files for the marker, while the client is open and after it is closed.
 *
 * @param directory the client's data directory; the database is created in it.
 * @param openClient the platform factory.
 * @return what was found where.
 */
internal suspend fun runClearLocalDataForensics(
    directory: Path,
    openClient: (SupabaseClient, SupabaseOfflineConfig) -> SupabaseOfflineClient,
): ClearLocalDataForensics {
    val marker = "MARKER${Random.nextLong().toULong().toString(16)}"
    val padding = "lorem ".repeat(60)
    var offline = false
    val server = RecordingPostgrestServer(
        answer = { request ->
            if (offline) connectivityFailure()
            val id = request.query["id"]?.removePrefix("eq.") ?: "0"
            okJson("""[{"id":$id,"email":"$marker-$id@example.com","bio":"$padding"}]""")
        },
    )
    val client = openClient(server.supabaseClient(), SupabaseOfflineConfig(offlineStoragePath = directory))
    val databasePath = directory / OFFLINE_DATABASE_FILE_NAME
    val wal = "$databasePath-wal".toPath()
    val shm = "$databasePath-shm".toPath()
    try {
        val profiles = client.from("profiles")
        repeat(CACHED_PAYLOADS) { index -> profiles.select { filter { eq("id", index) } } }
        offline = true
        profiles.insertJson(buildJsonArray { add(buildJsonObject { put("email", "$marker@example.com") }) })
        check(client.pendingMutationCount() == 1) { "the offline write was not queued" }
        check(containsMarker(databasePath, marker) || containsMarker(wal, marker)) { "the marker never reached the database" }

        // A screen still observing the table while it is cleared, as an app's would be: its reads
        // hold the database open while the clear rewrites it
        coroutineScope {
            val observer = launch(Dispatchers.Default) { runCatching { profiles.selectAsFlow().collect { } } }
            client.clearAllCache()
            observer.cancelAndJoin()
        }

        val whileOpen = Triple(containsMarker(databasePath, marker), containsMarker(wal, marker), containsMarker(shm, marker))
        client.close()
        return ClearLocalDataForensics(
            inDatabaseWhileOpen = whileOpen.first,
            inWalWhileOpen = whileOpen.second,
            inShmWhileOpen = whileOpen.third,
            inDatabaseAfterClose = containsMarker(databasePath, marker),
            inWalAfterClose = containsMarker(wal, marker),
            inShmAfterClose = containsMarker(shm, marker),
        )
    } finally {
        client.close()
    }
}

private const val CACHED_PAYLOADS = 40

private fun containsMarker(file: Path, marker: String): Boolean {
    if (!FileSystem.SYSTEM.exists(file)) return false
    return FileSystem.SYSTEM.read(file) { readByteString() }.indexOf(marker.encodeUtf8()) >= 0
}
