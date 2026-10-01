// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.BucketApi
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Entry point for the Supabase Offline SDK.
 *
 * Wraps a regular [SupabaseClient] and exposes:
 * - Offline-aware PostgREST builders (`from(...)`, `get(...)`)
 * - Offline-aware Storage bucket access (`storage(...)`)
 * - Manual mutation sync APIs (`syncAllPendingMutations`, `syncPendingMutations`)
 * - Introspection helpers for pending mutations (counts)
 *
 * Create one client per database and keep it for as long as the app uses it: a second client on the
 * same database fails to open until the first is [close]d.
 */
public interface SupabaseOfflineClient {

    /** Underlying online Supabase client. */
    public val supabase: SupabaseClient

    /**
     * Creates a new offline-aware [OfflinePostgrestQueryBuilder] for the given [table] in the
     * default schema.
     */
    public fun from(table: String): OfflinePostgrestQueryBuilder

    /**
     * Creates a new offline-aware [OfflinePostgrestQueryBuilder] for the given [schema] and
     * [table].
     */
    public fun from(schema: String, table: String): OfflinePostgrestQueryBuilder

    /** Operator sugar for `client["table"]` (default schema). */
    public operator fun get(table: String): OfflinePostgrestQueryBuilder = from(table)

    /** Operator sugar for `client["schema","table"]`. */
    public operator fun get(schema: String, table: String): OfflinePostgrestQueryBuilder =
        from(schema, table)

    /**
     * Returns the offline view of the Storage bucket [bucket]: every [BucketApi] operation, with
     * downloads cached on the device and served when offline. See [OfflineBucketApi].
     *
     * @param bucket the bucket identifier.
     * @return the bucket.
     */
    public fun storage(bucket: String): OfflineBucketApi

    /* ───────────────────────────── Sync APIs ───────────────────────────── */

    /**
     * Attempts to push **all** pending mutations (INSERT/UPSERT/UPDATE/DELETE) for **all tables**
     * to Supabase.
     *
     * - The replay first awaits a **settled** session (bounded): signed in, or signed out. While
     *   the session is `Initializing` or in the post-offline `RefreshFailure` window, requests
     *   would carry only the anon key whoever made the writes. If the session does not settle
     *   within the bound, the call returns 0 and leaves the queue intact for the next trigger.
     *   Clients without the Auth plugin skip the gate entirely (fail-open).
     * - The queue is kept per identity. Each mutation records who made it — a signed-in user, or
     *   nobody signed in — and a pass replays only the current session's mutations, across every
     *   table, in the order they were made. Another identity's mutations neither block it nor are
     *   sent by it: they replay when that identity is current again (signing out again replays the
     *   ones made signed out). The session is checked again before every request, so a sign-out or
     *   account switch stops the pass before its next one. Mutations queued by an earlier build of
     *   the engine, which recorded no owner, are adopted by the first identity to replay.
     * - A failure that may pass — no connectivity, a `401`, `408`, `429`, `5xx` or an unexpected
     *   error — stops the whole pass and keeps every remaining mutation queued, so a later write
     *   never overtakes an earlier one (a child row never replays ahead of its parent). Nothing
     *   transient ever removes a mutation. Replay failures are not thrown from this method.
     * - A mutation the server refuses for good (another `4xx`) is taken out of the queue and kept
     *   on a rejected list with the server's reason, and the pass continues.
     *
     * @return The number of mutations that were successfully synced.
     *
     * This function may still throw in truly unexpected situations (for example, if the underlying
     * Supabase client or plugins are misconfigured). Swift callers should wrap it in `try` /
     * `catch`.
     */
    @Throws(Exception::class) public suspend fun syncAllPendingMutations(): Int

    /**
     * Attempts to push all pending mutations for a single [table] to Supabase.
     *
     * - Awaits a settled session first, exactly like [syncAllPendingMutations]; an unsettled one
     *   defers the replay and returns 0.
     * - The current session's mutations for this [table] are replayed in the order they were made;
     *   another identity's are left alone.
     * - A network / connectivity failure stops syncing and the current (and remaining) mutations
     *   stay in the queue.
     * - Permanent failures (e.g. permission or constraint violations) move the failing mutation to
     *   the rejected list, and syncing continues with subsequent mutations. A `403` or `409` on a
     *   mutation that has an earlier mutation of the same identity pending in another table is held
     *   instead: that earlier write may be the row it depends on.
     * - Other server / application errors (`401`, `408`, `429`, `5xx`, unexpected) are propagated
     *   to the caller while keeping the failing mutation in the queue so it can be retried.
     *
     * @return The number of mutations that were successfully synced for [table].
     *
     * Swift callers should wrap this in `try` / `catch` to handle propagated errors from the
     * underlying Supabase client.
     */
    @Throws(Exception::class) public suspend fun syncPendingMutations(table: String): Int

    /**
     * Returns the number of the current session's pending mutations in the local queue across all
     * tables. Mutations another identity made — another user, or nobody signed in — are not
     * counted.
     *
     * Useful for:
     * - Showing "N changes to sync" badges
     * - Deciding whether to show a "Sync" button
     */
    @Throws(Exception::class) public suspend fun pendingMutationCount(): Int

    /**
     * Returns a [Flow] that emits the number of the current session's pending mutations across all
     * tables whenever it — or the session — changes. Collected after [close], or live at it, it
     * fails with [IllegalStateException].
     *
     * Useful for:
     * - Showing "N changes to sync" badges
     * - Deciding whether to show a "Sync" button
     */
    public fun pendingMutationCountFlow(): Flow<Int>

    /**
     * Returns the number of the current session's pending mutations for [table].
     *
     * Useful for table-specific UI ("this screen has unsynced edits").
     */
    @Throws(Exception::class) public suspend fun pendingMutationCount(table: String): Int

    /**
     * Returns a [Flow] that emits the number of the current session's pending mutations for [table]
     * whenever it — or the session — changes. Collected after [close], or live at it, it fails with
     * [IllegalStateException].
     *
     * Useful for table-specific UI ("this screen has unsynced edits").
     */
    public fun pendingMutationCountFlow(table: String): Flow<Int>

    /**
     * Applies a server-delivered row (e.g. a Realtime `postgres_changes` INSERT/UPDATE record) to
     * every cached SELECT payload for [table], merging by [primaryKeys] with upsert semantics: a
     * cached row whose [primaryKeys] columns all equal [record]'s is patched in place, otherwise
     * [record] is appended.
     *
     * Realtime consumers call this instead of reconstructing the SDK's internal cache-key format by
     * hand — the SDK patches every cached signature for [table] (filter-aware where the cache
     * tracks a query's filters), so callers never see or build a cache signature.
     *
     * No-ops when [table] has no cached SELECT payloads (nothing is observing it), and when any of
     * [primaryKeys] is absent from [record] or maps to a non-primitive (null / array / object)
     * value — the row can't be located, so the event is logged and dropped rather than risk
     * patching the wrong rows.
     *
     * It acts on the cached queries of [table] in the client's default schema. A table in another
     * schema, whose queries are cached apart, cannot be named here yet: a name written
     * `schema.table` may reach that schema's cached queries today, but is not a way to name one,
     * and stops reaching them once a schema can be named.
     *
     * @param table The Supabase table name (e.g. `"todos"`), in the client's default schema.
     * @param record The full row object as delivered by the Realtime event.
     * @param primaryKeys The column name(s) forming the row's identity; defaults to `["id"]`. Pass
     *   the table's actual primary key — e.g. `["team_id", "member_id"]` for a table whose primary
     *   key is composite and has no surrogate `id` column.
     */
    public suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String> = listOf("id"),
    )

    /**
     * Removes a server-deleted row from every cached SELECT payload for [table], matching on
     * [primaryKeys] against [oldRecord] (e.g. the `old_record` of a Realtime `postgres_changes`
     * DELETE event).
     *
     * No-ops under the same conditions as [applyServerRecord]: no cached payloads for [table], or a
     * [primaryKeys] column absent from [oldRecord] or non-primitive. Like it, it acts on [table] in
     * the client's default schema only.
     *
     * @param table The Supabase table name, in the client's default schema.
     * @param oldRecord The deleted row's prior column values, carrying at least [primaryKeys].
     * @param primaryKeys The column name(s) forming the row's identity; defaults to `["id"]`.
     */
    public suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String> = listOf("id"),
    )

    /**
     * Wipes the entire local offline cache. That is every cached SELECT payload, every cached
     * storage-object metadata row, every queued (pending or already-synced) mutation, **and** every
     * downloaded storage file under `<base>/storage/` on disk.
     *
     * This is a **local-only** operation — it does not touch the server. Identity-clearing flows
     * (logout, account switch, account deletion) must call this so one user's cached rows,
     * un-synced offline writes, and downloaded objects (e.g. avatars) never carry over to the next
     * user on the same device, and no personal data survives on disk after an account is deleted.
     *
     * With the Auth plugin, the cached queries and downloaded files also belong to one identity at
     * a time without this call: once the session settles as another user, or signed out, what was
     * cached before is no longer served, and is deleted and scrubbed from the database files as
     * this call does. Queued writes are kept then, each for the identity that made it; only this
     * call removes them. A session that is restoring, or whose refresh failed, keeps its cache.
     *
     * A replay pass in progress is cancelled first, and no queued write is sent once clearing has
     * begun. A write whose request was already in flight is neither overlaid onto the cache nor
     * queued when it returns, and a cache patch already under way when clearing begins writes
     * nothing after it. A Storage download still in flight may hand its bytes to its caller, or
     * fail, but it is not written back into the cache.
     *
     * The deleted rows are not left readable in the database files either: the database is rebuilt
     * without them and its write-ahead log emptied.
     */
    public suspend fun clearAllCache()

    /**
     * Releases the client and frees its database for another client. Nothing is deleted.
     *
     * What ends, and when:
     * - At once, every new call fails with [IllegalStateException] ("This SupabaseOfflineClient is
     *   closed"): on the client, and on a builder, bucket or flow obtained from it.
     * - At once, every flow being collected stops, whatever its read strategy: its collector reads
     *   that error next, even one stuck in its own body or one that has stopped reading. A channel
     *   the app holds through `produceIn` applied directly to the flow, and no longer reads, ends
     *   with it quietly, failing no scope. With an operator such as `map` between them, the closed
     *   error fails the producer: a plain `Job` scope is cancelled, and under a `SupervisorJob` the
     *   error stays in the unread channel.
     * - At once, the client's own work stops (refreshes, replay passes), and so does every Storage
     *   call and sync: syncs, downloads, uploads, updates, deletes, moves, copies, signed URLs,
     *   list, info and exists. Their callers get the same error, never a cancellation. A replay
     *   pass that has a queued write on the wire finishes that request and records its answer
     *   first, so the next client does not send the write again. A stopped Storage write may still
     *   have been applied, so the cached copies it touched are dropped: one extra download later,
     *   never a stale copy.
     * - Every other call already running is left to finish, and each of these can hold `close()` up
     *   to its bound: a PostgREST write, a clear, a one-shot `select` that goes to the network (its
     *   retries while a session restores included), a read of the cache, and the start or
     *   continuation of a resumable upload. A clear that has begun always completes, and a write
     *   whose answer arrives is applied and never sent again. The database is closed only once none
     *   of them is using it.
     *
     * Every call to `close()` returns once the database is released, or after 10 seconds of real
     * time at most, even under a test's virtual clock. Past that, the database is released as soon
     * as the last of those calls finishes; until then, creating a client on it fails with an error
     * saying it is still closing, and another `close()` waits for the release, up to the same
     * bound.
     *
     * A resumable upload obtained before `close()` is supabase-kt's own object and keeps uploading.
     * If it finishes after `close()`, the cached copy it replaced stays for the next client.
     *
     * Safe to call more than once, and from inside a collector of one of the client's flows, which
     * it ends at once. Called from inside a call it leaves to finish (an interceptor or token
     * provider of a PostgREST write, say), it waits out its full bound, since that call cannot end
     * first. A wrapper must forward it (delegation with `by` does), or the database stays claimed
     * and the wrapped client's work keeps running.
     */
    @Throws(Exception::class) public suspend fun close()
}
