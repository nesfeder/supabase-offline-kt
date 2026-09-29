package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.execSQL
import androidx.room.useWriterConnection
import kotlinx.coroutines.delay

/** How many times a checkpoint that readers held off is tried. */
private const val CHECKPOINT_ATTEMPTS: Int = 20

/** How long to wait between those tries. */
private const val CHECKPOINT_RETRY_MILLIS: Long = 50L

/**
 * Rewrites the database so deleted rows cannot be read back from its files or its write-ahead log:
 * `VACUUM`, then a `TRUNCATE` checkpoint, on Room's writer connection outside any transaction.
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
