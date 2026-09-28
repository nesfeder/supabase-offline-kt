package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.execSQL
import androidx.room.useWriterConnection
import kotlinx.coroutines.delay

/** How many times a checkpoint that readers held off is tried. */
private const val CHECKPOINT_ATTEMPTS: Int = 20

/** How long to wait between those tries. */
private const val CHECKPOINT_RETRY_MILLIS: Long = 50L

/**
 * Rewrites the database so rows deleted from it cannot be read back from its files.
 *
 * A `DELETE` only marks pages free. Their bytes stay in the database file — Apple's SQLite, whose
 * `secure_delete` is FAST, leaves them as they were — and every earlier version of those pages
 * stays in the write-ahead log until a checkpoint overwrites or truncates it, which on Android,
 * where the process is killed rather than closed, can be never. `VACUUM` rebuilds the database
 * from its live rows alone, and a `TRUNCATE` checkpoint copies the result into the database file
 * and empties the log.
 *
 * Runs on Room's writer connection, outside any transaction, as `VACUUM` requires.
 *
 * @return `true` when the write-ahead log was emptied; `false` when open reads held the checkpoint
 *   off on every try, which leaves the old pages in the log until the next checkpoint.
 */
internal suspend fun OfflineDatabase.scrubDeletedRows(): Boolean = useWriterConnection { connection ->
    connection.execSQL("VACUUM")
    repeat(CHECKPOINT_ATTEMPTS) {
        val blocked = connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { statement ->
            statement.step()
            statement.getLong(0)
        }
        if (blocked == 0L) return@useWriterConnection true
        delay(CHECKPOINT_RETRY_MILLIS)
    }
    false
}
