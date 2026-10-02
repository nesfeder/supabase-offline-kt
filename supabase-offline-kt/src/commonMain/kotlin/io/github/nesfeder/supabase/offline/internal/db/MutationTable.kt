// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A queued mutation (INSERT / UPSERT / UPDATE / DELETE) for a given PostgREST table, with the
 * options of supabase-kt's request builders it replays with, the identity that made it, and where
 * its send and its replay stand. The overlay shows it on cached reads until it is retired.
 */
@Entity(
    tableName = "mutation_cache",
    indices = [Index(value = ["owner", "synced_at_ms"]), Index(value = ["table_name"])],
)
internal data class MutationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "table_name") val table: String,

    /** "INSERT", "UPSERT", "UPDATE", or "DELETE". */
    val operation: String,

    /**
     * Raw JSON body string. For INSERT/UPSERT usually a JSON array; for UPDATE a JSON object; for
     * DELETE usually "{}".
     */
    @ColumnInfo(name = "body_json") val bodyJson: String,

    /** The JSON encoding of the `FilterDescriptor` the write targets rows by; `null` for none. */
    @ColumnInfo(name = "filter_json") val filterJson: String?,

    /**
     * Client-side timestamp (epoch millis) when the mutation was enqueued. Informational only:
     * replay and overlay order by [id], because a wall clock can step backwards.
     */
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,

    /**
     * When the mutation replayed, by the device clock; `null` while it is pending. Informational:
     * which cached payloads already hold it is decided by [syncSequence].
     */
    @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,

    /** Mirrors the INSERT and UPSERT builders' `defaultToNull`; ignored for UPDATE and DELETE. */
    @ColumnInfo(name = "default_to_null") val defaultToNull: Boolean? = null,

    /** Mirrors the UPSERT builder's `onConflict`: its comma-separated conflict columns. */
    @ColumnInfo(name = "on_conflict") val onConflict: String? = null,

    /** Mirrors the UPSERT builder's `ignoreDuplicates`; ignored for other operations. */
    @ColumnInfo(name = "ignore_duplicates") val ignoreDuplicates: Boolean? = null,

    /**
     * The stored value of the identity that made the write; `null` when none was recorded, for a
     * write the engine queued with no session model.
     */
    @ColumnInfo(name = "owner") val owner: String? = null,

    /**
     * The order in which the mutation replayed among all synced mutations, from a sequence that
     * never goes back; `null` while it is pending.
     */
    @ColumnInfo(name = "sync_sequence") val syncSequence: Long? = null,

    /**
     * How many replays PostgREST refused because its schema cache did not know the table or a
     * column yet; the write is rejected once they reach the bound.
     */
    @ColumnInfo(name = "schema_cache_misses", defaultValue = "0") val schemaCacheMisses: Int = 0,

    /**
     * Whether a send of this INSERT started and its outcome is unknown. The server may then hold
     * its rows, so it replays as an upsert that takes them as its own. Set only for an INSERT whose
     * rows all carry the table's key, before its request goes out; cleared on proof that the write
     * did not run, and when it replays.
     */
    @ColumnInfo(name = "send_unconfirmed", defaultValue = "0") val sendUnconfirmed: Boolean = false,
)

@Dao
internal interface MutationDao {

    /**
     * Stores [row]. A row whose [MutationRow.id] is `0` receives the next autoincrement id.
     *
     * @param row The mutation to store.
     * @return The row's id: the one it was given, or the one SQLite assigned.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(row: MutationRow): Long

    /**
     * Pending mutations for a specific table, in insertion order.
     *
     * Only rows with [MutationRow.syncedAtMs] == null are considered pending. The order is the
     * autoincrement [MutationRow.id], never the wall clock: `created_at_ms` can step backwards (a
     * clock change, an NTP correction) and ties within a millisecond, and replay order has to be
     * the order the writes were made.
     */
    @Query(
        "SELECT * FROM mutation_cache " +
            "WHERE table_name = :table AND synced_at_ms IS NULL " +
            "ORDER BY id ASC"
    )
    suspend fun pendingForTable(table: String): List<MutationRow>

    /** All pending mutations across all tables, in insertion order (by [MutationRow.id]). */
    @Query("SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL ORDER BY id ASC")
    suspend fun pendingAll(): List<MutationRow>

    /**
     * Every pending mutation across all tables, in insertion order, re-emitted on every queue
     * change.
     */
    @Query("SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL ORDER BY id ASC")
    fun pendingAllFlow(): Flow<List<MutationRow>>

    /**
     * The pending mutations of [owner] — and with [includeOwnerless] those with no recorded owner —
     * in insertion order.
     */
    @Query(
        "SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL " +
            "AND (owner = :owner OR (:includeOwnerless AND owner IS NULL)) ORDER BY id ASC"
    )
    suspend fun pendingOwnedBy(owner: String, includeOwnerless: Boolean): List<MutationRow>

    /** How many mutations [pendingOwnedBy] would return, without reading them. */
    @Query(
        "SELECT COUNT(*) FROM mutation_cache WHERE synced_at_ms IS NULL " +
            "AND (owner = :owner OR (:includeOwnerless AND owner IS NULL))"
    )
    suspend fun countPendingOwnedBy(owner: String, includeOwnerless: Boolean): Int

    /** How many mutations [pendingOwnedByInTable] would return, without reading them. */
    @Query(
        "SELECT COUNT(*) FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NULL " +
            "AND (owner = :owner OR (:includeOwnerless AND owner IS NULL))"
    )
    suspend fun countPendingOwnedByInTable(
        owner: String,
        includeOwnerless: Boolean,
        table: String,
    ): Int

    /** [pendingOwnedBy], of [table] only. */
    @Query(
        "SELECT * FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NULL " +
            "AND (owner = :owner OR (:includeOwnerless AND owner IS NULL)) ORDER BY id ASC"
    )
    suspend fun pendingOwnedByInTable(
        owner: String,
        includeOwnerless: Boolean,
        table: String,
    ): List<MutationRow>

    /**
     * Whether any mutation of [owner], or with [includeOwnerless] any with no recorded owner, is
     * pending with an id below [beforeId]. Reads the owner index only.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM mutation_cache WHERE owner = :owner " +
            "AND synced_at_ms IS NULL AND id < :beforeId) OR (:includeOwnerless AND EXISTS(" +
            "SELECT 1 FROM mutation_cache WHERE owner IS NULL AND synced_at_ms IS NULL " +
            "AND id < :beforeId))"
    )
    suspend fun hasPendingOwnedBy(
        owner: String,
        includeOwnerless: Boolean,
        beforeId: Long,
    ): Boolean

    /** Records [owner] as the owner of mutation [id]. */
    @Query("UPDATE mutation_cache SET owner = :owner WHERE id = :id")
    suspend fun setOwner(id: Long, owner: String)

    /**
     * Records [owner] for every pending mutation that has none.
     *
     * @return How many it adopted.
     */
    @Query("UPDATE mutation_cache SET owner = :owner WHERE owner IS NULL AND synced_at_ms IS NULL")
    suspend fun adoptOwnerless(owner: String): Int

    /** Whether the mutation [id] is queued and not yet synced. */
    @Query("SELECT EXISTS(SELECT 1 FROM mutation_cache WHERE id = :id AND synced_at_ms IS NULL)")
    suspend fun isPending(id: Long): Boolean

    /** The mutation [id], pending or synced, or `null` when it is not queued. */
    @Query("SELECT * FROM mutation_cache WHERE id = :id") suspend fun byId(id: Long): MutationRow?

    /** [table]'s synced mutations still retained for the overlay, oldest replay first. */
    @Query(
        "SELECT * FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NOT NULL " +
            "ORDER BY sync_sequence ASC, id ASC"
    )
    suspend fun syncedForTable(table: String): List<MutationRow>

    /** Every table with a synced mutation retained for the overlay. */
    @Query("SELECT DISTINCT table_name FROM mutation_cache WHERE synced_at_ms IS NOT NULL")
    suspend fun tablesWithSyncedMutations(): List<String>

    /** The highest [MutationRow.syncSequence] of any row; `0` when there is none. */
    @Query("SELECT COALESCE(MAX(sync_sequence), 0) FROM mutation_cache")
    suspend fun maxSyncSequence(): Long

    /** Counts one more schema-cache miss for mutation [id]. */
    @Query("UPDATE mutation_cache SET schema_cache_misses = schema_cache_misses + 1 WHERE id = :id")
    suspend fun recordSchemaCacheMiss(id: Long)

    @Query("SELECT COUNT(*) FROM mutation_cache") suspend fun countAll(): Int

    @Query("DELETE FROM mutation_cache WHERE id = :id") suspend fun deleteById(id: Long)

    /**
     * All mutations for a table (pending + already-synced), in insertion order (by
     * [MutationRow.id]). Used when applying overlays on top of cached SELECT data while offline.
     */
    @Query("SELECT * FROM mutation_cache WHERE table_name = :table ORDER BY id ASC")
    suspend fun allForTable(table: String): List<MutationRow>

    /**
     * Marks mutation [id] synced: when, by the device clock, and its [MutationRow.syncSequence].
     */
    @Query(
        "UPDATE mutation_cache SET synced_at_ms = :syncedAtMs, sync_sequence = :syncSequence, " +
            "send_unconfirmed = 0 WHERE id = :id"
    )
    suspend fun markSynced(id: Long, syncedAtMs: Long, syncSequence: Long)

    /**
     * Records that a send of mutation [id] started ([MutationRow.sendUnconfirmed]).
     *
     * @return `1` when this call set it, `0` when it was set already or there is no such row.
     */
    @Query("UPDATE mutation_cache SET send_unconfirmed = 1 WHERE id = :id AND send_unconfirmed = 0")
    suspend fun markSendUnconfirmed(id: Long): Int

    /** Clears [MutationRow.sendUnconfirmed] of mutation [id]: its send never reached the server. */
    @Query("UPDATE mutation_cache SET send_unconfirmed = 0 WHERE id = :id")
    suspend fun clearSendUnconfirmed(id: Long)

    /** The ids of every mutation whose send started and whose outcome is unknown. */
    @Query("SELECT id FROM mutation_cache WHERE send_unconfirmed = 1")
    suspend fun unconfirmedSendIds(): List<Long>

    /** Deletes every queued mutation (pending + already-synced) across all tables. */
    @Query("DELETE FROM mutation_cache") suspend fun deleteAll()

    /**
     * Runs [block] in one write transaction: every DAO call it makes, on any of the database's
     * DAOs, commits together or not at all. Keep [block] to DAO calls — never wait on a coroutine
     * lock inside it, or a writer holding that lock could wait on this transaction's connection.
     *
     * @param block The writes to make atomically.
     */
    @Transaction
    suspend fun runInTransaction(block: suspend () -> Unit) {
        block()
    }
}

/**
 * Runs [block] in one write transaction, as [MutationDao.runInTransaction] does, and returns its
 * value. The same rule holds: DAO calls only, never a wait on a coroutine lock.
 */
internal suspend fun <T> MutationDao.inTransaction(block: suspend () -> T): T {
    var result: TransactionResult<T>? = null
    runInTransaction { result = TransactionResult(block()) }
    return checkNotNull(result) { "The transaction did not run its block" }.value
}

/** What a transaction's block returned, so a `null` result is told from a block that never ran. */
private class TransactionResult<T>(val value: T)

/** Simple operation "enum" so we don't rely on Room @TypeConverter right away. */
internal object MutationOperation {
    const val INSERT = "INSERT"
    const val UPSERT = "UPSERT"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
}
