// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.encodeToJsonElement
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.plugins.SupabasePlugin
import io.github.jan.supabase.plugins.SupabasePluginProvider
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.request.RpcRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.OfflinePlugin
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DEFAULT_PRIMARY_KEY
import io.github.nesfeder.supabase.offline.internal.openPlatformClient
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeFeed
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.internal.realtime.toWatchedTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The offline plugin for supabase-kt: PostgREST reads served from a cache on the device, writes
 * queued while the network is down and sent when it is back, and Storage downloads kept on disk.
 *
 * Install it beside [Postgrest] (and [Storage], if you use it) and reach it as [offline], as
 * supabase-kt's own plugins are reached:
 * ```kotlin
 * val supabase = createSupabaseClient(url, key) {
 *     install(Auth)
 *     install(Postgrest)
 *     install(Storage)
 *     install(Offline) { cacheSizeBytes = 50L * 1024 * 1024 }
 * }
 * val todos = supabase.offline.from("todos").select { filter { eq("done", false) } }
 * ```
 *
 * Most calls written against supabase-kt's `supabase.from(...)` and `supabase.storage.from(...)`
 * compile against `supabase.offline.from(...)` and `supabase.offline.storage.from(...)` with only
 * the receiver changed and [offline] imported. A write returns [MutationOutcome] instead of the
 * server's result. A suspending call or an early `return` inside a request lambda, a request lambda
 * passed inside the parentheses, realtime's `selectAsFlow` and the typed [rpc]'s import need an
 * edit as well; [OfflinePostgrestQueryBuilder] lists each. Bucket management (`createBucket`,
 * `listBuckets` and the rest) stays on `supabase.storage`, and realtime on `supabase.from(...)`.
 *
 * The plugin opens its database when the client is created, and one database serves one client at a
 * time: creating a second client with the plugin over the same data directory throws until the
 * first is closed. It is closed with the client ([SupabaseClient.close]), or on its own with
 * [close].
 *
 * Opening it touches the file system on the thread that calls `createSupabaseClient`: it claims the
 * database, creates its directory, and may move a database left at the default location into
 * [Config.dataDirectory]. Build the client off the main thread when that matters. Install the
 * plugin last, as the examples do: when a plugin installed after it fails to set up, the client is
 * never returned and the database stays claimed until the process ends.
 *
 * ### Android backups
 *
 * The database holds cached rows and writes not yet sent, and the file cache downloaded objects:
 * all of it re-creatable, and one user's. Android's Auto Backup includes both by default, so a
 * restore onto a new device would bring them back under whoever signs in next. Leave them out in
 * the app's backup rules; for Android 12 and later (`android:dataExtractionRules`), with the
 * default [Config.dataDirectory]:
 * ```xml
 * <data-extraction-rules>
 *     <cloud-backup>
 *         <exclude domain="database" path="supabase_offline.db" />
 *         <exclude domain="database" path="supabase_offline.db-wal" />
 *         <exclude domain="database" path="supabase_offline.db-shm" />
 *         <exclude domain="file" path="supabase_offline/" />
 *     </cloud-backup>
 *     <device-transfer>
 *         <!-- the same four lines -->
 *     </device-transfer>
 * </data-extraction-rules>
 * ```
 *
 * and the same four lines in a `<full-backup-content>` file for Android 11 and earlier
 * (`android:fullBackupContent`). With a data directory set, exclude that directory instead. On iOS
 * the plugin excludes its files from backups itself.
 *
 * Only the library implements this interface; [OfflineInternal] marks it so. Tests build a client
 * with the `supabase-offline-kt-testing` artifact.
 */
@SubclassOptInRequired(OfflineInternal::class)
public interface Offline : SupabasePlugin<Offline.Config> {

    /**
     * The [table] in the client's default schema, read through the cache and written through the
     * mutation queue.
     *
     * @param table the table or view.
     * @return the table's builder.
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalStateException when [Postgrest] is not installed.
     */
    public fun from(table: String): OfflinePostgrestQueryBuilder

    /**
     * The [table] in [schema], read through the cache. Writes to a schema other than the client's
     * default are sent at once and never queued; see [MutationStrategy.QueueIfOffline].
     *
     * @param schema the schema.
     * @param table the table or view.
     * @return the table's builder.
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalStateException when [Postgrest] is not installed.
     */
    public fun from(schema: String, table: String): OfflinePostgrestQueryBuilder

    /**
     * The [table] in the client's default schema; `offline["todos"]` is `offline.from("todos")`.
     *
     * @param table the table or view.
     * @return the table's builder.
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalStateException when [Postgrest] is not installed.
     */
    public operator fun get(table: String): OfflinePostgrestQueryBuilder = from(table)

    /**
     * The [table] in [schema]; `offline["app", "todos"]` is `offline.from("app", "todos")`.
     *
     * @param schema the schema.
     * @param table the table or view.
     * @return the table's builder.
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalStateException when [Postgrest] is not installed.
     */
    public operator fun get(schema: String, table: String): OfflinePostgrestQueryBuilder =
        from(schema, table)

    /**
     * Calls the database function [function] on the server, as `supabase.postgrest.rpc` does.
     *
     * Network only: nothing is cached and nothing is queued, since a function may change data. It
     * fails offline with supabase-kt's own error.
     *
     * @param function the function's name.
     * @param request the request: the HTTP method, a count, filters on the result.
     * @return the server's response.
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalStateException when [Postgrest] is not installed.
     */
    @Throws(Throwable::class)
    public suspend fun rpc(
        function: String,
        request: RpcRequestBuilder.() -> Unit = {},
    ): PostgrestResult

    /**
     * Calls the database function [function] with [parameters] on the server, as
     * `supabase.postgrest.rpc` does. Network only, like the overload without parameters.
     *
     * @param function the function's name.
     * @param parameters the function's arguments, by name.
     * @param request the request: the HTTP method, a count, filters on the result.
     * @return the server's response.
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalStateException when [Postgrest] is not installed.
     */
    @Throws(Throwable::class)
    public suspend fun rpc(
        function: String,
        parameters: JsonObject,
        request: RpcRequestBuilder.() -> Unit = {},
    ): PostgrestResult

    /**
     * Storage buckets, with downloads kept on the device: `offline.storage.from("avatars")`, as
     * `supabase.storage.from("avatars")`. Needs [Storage] installed.
     */
    public val storage: OfflineStorage

    /**
     * The current user's writes waiting in the mutation queue, of [table] or of every table, in the
     * order they will be sent, as the queue changes.
     *
     * The current user's are the writes made under their session, and those queued with no user
     * recorded that they may take over; never another user's. While the stored session cannot be
     * read, the list is empty. A collection live at [close] completes, so `first()` on one that has
     * not emitted yet throws [NoSuchElementException] (`firstOrNull()` returns `null`); collected
     * after [close], the flow fails with [IllegalStateException].
     *
     * @param table the table, or `null` for every table.
     * @return the flow of lists.
     */
    public fun pendingMutations(table: String? = null): Flow<List<PendingMutation>>

    /**
     * How many of the current user's writes wait in the mutation queue, of [table] or of every
     * table; see [pendingMutations] for whose they are.
     *
     * @param table the table, or `null` for every table.
     * @return the count.
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun pendingMutationCount(table: String? = null): Int

    /**
     * [pendingMutationCount] as the queue changes; a value is emitted only when it differs from the
     * last. A collection live at [close] completes, so `first()` on one that has not emitted yet
     * throws [NoSuchElementException]; collected after [close], the flow fails with
     * [IllegalStateException].
     *
     * @param table the table, or `null` for every table.
     * @return the flow of counts.
     */
    public fun pendingMutationCountFlow(table: String? = null): Flow<Int>

    /**
     * Removes one of the current user's queued writes without sending it: **destructive**. The
     * write is lost, and the rows it changed show again as the server last sent them. It is the way
     * out of a write the server keeps failing (a `5xx` it never stops answering), which holds every
     * later write of the user behind it; once it is gone, what waited behind it is sent.
     *
     * When the write is being sent at that moment, this waits for the send to end, then discards it
     * if it is still queued. Nothing happens when no queued write of the current user has this id:
     * it was sent or refused meanwhile, it is another user's, or there is none.
     *
     * @param id the write's [PendingMutation.id].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun discardPendingMutation(id: Long)

    /**
     * The current user's queued writes the server refused for good when they were replayed, oldest
     * first (the queue's order; show the newest first if you like), as the list changes: what an
     * app shows so its user can fix or drop a write that will never go through on its own.
     *
     * The list is kept on the device across restarts until each entry is dismissed or retried, and
     * holds the newest 100 entries of every user together, within a size bound; when it is full,
     * the oldest entries go, and their writes are lost, with nothing to report it. Let the user
     * dismiss or retry each entry, so the list stays short. It shows the current user's entries,
     * and those with no user recorded that they may take over; never another user's.
     *
     * A write sent at once, because nothing of the user was queued ahead of it, that the server
     * refuses is not listed: its caller gets the server's error, as from supabase-kt. A collection
     * live at [close] completes, so `first()` on one that has not emitted yet throws
     * [NoSuchElementException]; collected after [close], the flow fails with
     * [IllegalStateException].
     *
     * @return the flow of lists.
     */
    public fun rejectedMutations(): Flow<List<RejectedMutation>>

    /**
     * Removes a refused write from the rejected list for good. Nothing happens when the current
     * user has no entry with this id.
     *
     * @param id the entry's [RejectedMutation.id].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun dismissRejectedMutation(id: Long)

    /**
     * Queues a refused write again, at the end of the current user's queue and under a new id, and
     * asks for it to be sent: for when the cause has been fixed, such as a missing parent row or a
     * policy. It leaves the rejected list. Nothing happens when the current user has no entry with
     * this id.
     *
     * @param id the entry's [RejectedMutation.id].
     * @throws IllegalArgumentException when the entry cannot be retried
     *   ([RejectedMutation.isRetryable] is `false`).
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun retryRejectedMutation(id: Long)

    /**
     * The current user's queued writes parked because their row moved on the server and the
     * conflict was not settled during the replay ([ConflictResolution.Hold], a [ConflictResolver]
     * that failed or did not answer in time, or a row that kept moving), oldest first, as the list
     * changes: what an app shows so its user can choose between their change and the server's.
     *
     * The list is kept on the device across restarts until each entry is resolved or dismissed, and
     * holds the newest 100 entries of every user together, within a size bound, as
     * [rejectedMutations] does; when it is full, the oldest entries go, and their writes are lost.
     * It shows the current user's entries, and those with no user recorded that they may take over;
     * never another user's. Only a write to a table with a version column ([Config.versionColumns])
     * can end up here. A collection live at [close] completes, so `first()` on one that has not
     * emitted yet throws [NoSuchElementException]; collected after [close], the flow fails with
     * [IllegalStateException].
     *
     * @return the flow of lists.
     */
    public fun conflictedMutations(): Flow<List<ConflictedMutation>>

    /**
     * Settles a parked write. [ConflictResolution.KeepLocal] and [ConflictResolution.Apply] queue
     * it again, at the end of the current user's queue and under a new id, preconditioned on the
     * server version the entry recorded ([ConflictedMutation.serverVersion]), and ask for it to be
     * sent: when the row has moved again since, the replay finds a new conflict.
     * [ConflictResolution.KeepServer] drops it. Either way it leaves the conflicted list. Nothing
     * happens when the current user has no entry with this id.
     *
     * @param id the entry's [ConflictedMutation.id].
     * @param resolution how to settle it.
     * @throws IllegalArgumentException for [ConflictResolution.Hold]; for
     *   [ConflictResolution.Apply] on an entry that is not an UPDATE of a row the server still
     *   held; and for [ConflictResolution.KeepLocal] on an entry whose row was gone from the server
     *   ([ConflictedMutation.serverVersion] is `null`) or whose body was too large to keep.
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun resolveConflictedMutation(id: Long, resolution: ConflictResolution)

    /**
     * Removes a parked write from the conflicted list for good, without sending it. Nothing happens
     * when the current user has no entry with this id.
     *
     * @param id the entry's [ConflictedMutation.id].
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun dismissConflictedMutation(id: Long)

    /**
     * Sends the current user's queued writes, of [table] or of every table, in the order they were
     * made, and reports what happened. Returns promptly when offline, with the writes still queued.
     *
     * The pass first waits, for a bounded time, for the session to settle (signed in, or signed
     * out); it does not wait when the network is known to be down. A failure that may pass (no
     * connection, a `401`, `408`, `429` or `5xx`) stops it with every remaining write still queued,
     * so no write overtakes an earlier one; it is not thrown, and [SyncStatus.lastSyncError] says
     * what it was. A write the server refuses for good (another `4xx`) goes to [rejectedMutations],
     * and the pass goes on. A guarded write whose row moved on the server ([Config.versionColumns])
     * is settled by [Config.conflictResolver] and sent again, dropped, or parked on
     * [conflictedMutations], and the pass goes on. For a single table, the pass stops at the first
     * write that has an earlier write of the user still queued in another table, which may be the
     * row it depends on: no write is sent ahead of an earlier one, and
     * [SyncReport.heldBehindOtherTablesCount] counts what it held back.
     *
     * When the library is sending the same writes at that moment, by itself or for another call,
     * this waits for it to let go and then sends what is left, so it returns only once nothing more
     * can be sent now: [SyncReport.remainingCount] is what is still queued after both, and the
     * report's other counts are what this call sent. While network access is off ([disableNetwork])
     * it sends nothing.
     *
     * Nothing sends the queue when the app comes to the foreground: a write that failed in a way
     * that may pass waits for the library's next pass, up to a minute later, or as long as a
     * server's `Retry-After` asks. Call this as the app comes to the foreground to send at once.
     *
     * @param table the table, or `null` for every table.
     * @return what this call sent, what was refused, and what is still queued.
     * @throws OfflineClientClosedException when the client is closed.
     * @throws IllegalStateException when [Postgrest] is not installed.
     */
    @Throws(Throwable::class)
    public suspend fun syncPendingMutations(table: String? = null): SyncReport

    /**
     * Where the library stands with the current user's queued writes and the network, as it
     * changes: one value to draw a sync banner from. See [SyncStatus] for when each part is
     * current.
     *
     * It follows the queue from the moment the client opens, so shortly after, its counts include
     * the writes an earlier run left queued, whether or not the app has read it yet. The same flow
     * whether or not the client is open, and it never throws. At [close] it takes one last value,
     * with [SyncStatus.isSyncing] `false`, and changes no more; read after [close], it holds that
     * value.
     */
    public val syncStatus: StateFlow<SyncStatus>

    /**
     * Turns the library's network access off, as an airplane-mode switch in an app or a test does:
     * until [enableNetwork], it sends nothing. Reads are answered from the cache as offline,
     * reactive reads report [RefreshState.Paused], writes that may queue are queued, a sync pass
     * sends nothing, and a Storage download is answered from the file cache. A request that must
     * reach the server (a [ReadStrategy.NetworkOnly] read, a [MutationStrategy.OnlineOnly] write,
     * an [rpc], a Storage call other than a download) throws [NetworkDisabledException]. A request
     * already on the wire still answers, and so does a resumable upload created or paused while
     * access was on: it sends its chunks until it ends.
     *
     * [SyncStatus.network] is [NetworkStatus.DISABLED] as soon as this returns. The switch is not
     * kept: a new client starts with network access on. Calling it again changes nothing.
     *
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun disableNetwork()

    /**
     * Turns the library's network access back on after [disableNetwork]. A reactive read refreshes,
     * and with [Config.autoSync] queued writes are sent, as when the network comes back.
     * [SyncStatus.network] is the monitor's again as soon as this returns. Calling it while access
     * is on changes nothing.
     *
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun enableNetwork()

    /**
     * Where the plugin stands with the channel that keeps the tables [Config.realtime] named
     * current, as it changes: [RealtimeStatus.Off] when none is named. It never throws. At [close]
     * it takes [RealtimeStatus.Off], the channel having been left, and changes no more.
     */
    public val realtimeStatus: StateFlow<RealtimeStatus>

    /**
     * Leaves the Realtime channel and joins none until [enableRealtime]: an app's own switch for
     * the tables [Config.realtime] named. [realtimeStatus] reads [RealtimeStatus.Off] once this
     * returns, and the channel is left right after. Like [disableNetwork], the switch is not kept:
     * a new client starts with it on. Nothing happens without named tables, or while already off.
     *
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun disableRealtime()

    /**
     * Joins the channel again after [disableRealtime], as soon as a user is signed in (or [Auth] is
     * not installed) and the network is not known down. Nothing happens while already on.
     *
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun enableRealtime()

    /**
     * Applies a row the server reports, such as a Realtime `postgres_changes` INSERT or UPDATE
     * record, to every cached query of [table]: the cached row with the same key is replaced, and a
     * query that admits the row and does not hold it gains it.
     *
     * Nothing happens when nothing of [table] is cached, or when [record] lacks a key column or
     * holds one as `null`, an array or an object: the row cannot be located, so the record is
     * logged and dropped.
     *
     * It acts on [table] in [schema], the client's default schema unless named: a table of another
     * schema is cached apart from one of the same name in the default schema.
     *
     * This keeps the cache current between refreshes. It does not replace them: Realtime does not
     * replay what happened while the device was disconnected, so read again after a reconnect.
     *
     * @param table the table.
     * @param record the row as the server sent it.
     * @param primaryKeys the columns that identify the row, or `null` for the table's primary key
     *   as [Config.primaryKeys] gives it: `id` unless configured there.
     * @param schema the table's schema, or `null` for the client's default schema.
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>? = null,
        schema: String? = null,
    )

    /**
     * Removes a row the server reports deleted, such as the `old_record` of a Realtime
     * `postgres_changes` DELETE, from every cached query of [table].
     *
     * Nothing happens under the same conditions as for [applyServerRecord], and it too acts on
     * [table] in [schema], the client's default schema unless named.
     *
     * @param table the table.
     * @param oldRecord the deleted row's values, carrying at least its key.
     * @param primaryKeys the columns that identify the row, or `null` for the table's primary key
     *   as [Config.primaryKeys] gives it: `id` unless configured there.
     * @param schema the table's schema, or `null` for the client's default schema.
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String>? = null,
        schema: String? = null,
    )

    /**
     * Deletes everything the library keeps on this device: cached query results, downloaded Storage
     * files, **and every write still waiting in the mutation queue, which is never sent**, with the
     * rejected and conflicted lists. It does not touch the server.
     *
     * Call it when a user signs out for good, switches account, or deletes their account, so one
     * user's rows, unsent writes and files never reach the next. With [Auth] installed, the cache
     * already belongs to one user at a time (it is dropped when the session settles as someone
     * else), but queued writes stay with the user who made them until this call. Clear before
     * `auth.signOut()`, or in a `finally` around it: offline, `signOut()` throws and keeps the
     * session, so a clear after it would never run.
     *
     * A replay pass in progress is canceled first, and nothing queued is sent once clearing has
     * begun. A write already on the wire is neither applied to the cache nor queued when it
     * answers. A Storage download in flight may hand its bytes to its caller, or fail, but is not
     * written back.
     *
     * Keeping the deleted rows out of the database's files is best effort. The database is
     * rewritten without them, and its write-ahead log, which still holds them, is emptied once no
     * open read holds it: this waits up to a second for such reads, and when one outlasts that the
     * library empties the log within a few seconds of the read ending, and once more at [close]. It
     * never fails the clear, and never waits longer.
     *
     * The client stays usable afterward. A live read open through the clear that refreshes (any
     * strategy but [FlowReadStrategy.CacheOnly]) fetches again, as it does when the cache passes to
     * another identity: at once online, and once the network is back offline, [RefreshState.Paused]
     * meanwhile.
     *
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun clearLocalData()

    /**
     * Closes the plugin and frees its database for another client. Nothing is deleted.
     *
     * [SupabaseClient.close] calls it after supabase-kt has begun closing its HTTP client. A call
     * made in between, or one already running whose request had not gone out, is either still sent
     * (Ktor can send a request while its client finishes closing) or ends as it would offline: a
     * read the cache can answer is answered from it, a write the mutation queue holds returns
     * [MutationOutcome.Queued], and anything else fails with the closed error below, never with a
     * cancellation. A request already on the wire still answers. Call this before
     * [SupabaseClient.close] to let the calls not yet sent go out.
     *
     * What ends, and when:
     * - At once, every new call fails with [OfflineClientClosedException]: on the plugin, and on a
     *   builder, bucket or flow obtained from it. [syncStatus] is the exception: it can still be
     *   read, and holds its last value.
     * - At once, every flow being collected completes, whatever its read strategy, so a screen torn
     *   down with the client sees its flow end, not an error; a channel the app holds through
     *   `produceIn`, and no longer reads, ends with it, failing no scope. [syncStatus], a
     *   `StateFlow`, cannot end: it takes one last value, with [SyncStatus.isSyncing] `false`, and
     *   changes no more.
     * - At once, the plugin's own work stops (refreshes, replay passes), and so does every Storage
     *   call and sync; their callers get the same error, never a cancellation. A replay pass with a
     *   queued write on the wire finishes that request and records its answer first, so the next
     *   client does not send the write again. A stopped Storage write may still have been applied,
     *   so the cached copies it touched are dropped.
     * - Every other call already running is left to finish: a PostgREST write, a clear, a one-shot
     *   `select` that goes to the network, an [rpc], a read of the cache, and the start or
     *   continuation of a resumable upload. A clear that has begun always completes, and a write
     *   whose answer arrives is applied and never sent again.
     *
     * Every call returns once the database is released, or after 10 seconds of real time at most,
     * even under a test's virtual clock. Past that, the database is released as soon as the last of
     * those calls finishes; until then, a new client on it fails with an error saying it is still
     * closing. It never throws: a failure closing the database is logged, and the database is
     * released all the same. Safe to call more than once, and from inside a collector of one of the
     * plugin's flows.
     */
    override suspend fun close()

    /**
     * How the plugin is set up, in `install(Offline) { … }`.
     *
     * Every setting has a default that suits most apps; set only what you need. The plugin reads
     * the configuration once, when the client is created: changing `supabase.offline.config`
     * afterward has no effect.
     */
    public class Config internal constructor() {

        /**
         * The most the cached query results may hold, in bytes, counted as the summed length of the
         * cached responses (not the database file, which SQLite never shrinks). When it is passed,
         * the results fetched longest ago are evicted first; a query a live reader shows is kept.
         * [CACHE_SIZE_UNLIMITED] turns eviction off. Below 4 KiB counts as 4 KiB. 100 MiB by
         * default.
         */
        public var cacheSizeBytes: Long = DEFAULT_CACHE_SIZE_BYTES

        /**
         * The most the Storage file cache may hold, in bytes. When a download passes it, the least
         * recently used files are deleted until it fits; a file being read, and one used in the
         * last 30 seconds, are kept. [CACHE_SIZE_UNLIMITED] turns eviction off; below zero counts
         * as zero. 256 MiB by default.
         */
        public var fileCacheSizeBytes: Long = DEFAULT_FILE_CACHE_SIZE_BYTES

        /**
         * The directory for everything the plugin keeps on the device: the database
         * (`supabase_offline.db`) and the Storage file cache. `null`, the default, keeps them where
         * the platform's convention puts them: the database in the app's database directory and the
         * files under `<filesDir>/supabase_offline` on Android, both under `Library/Application
         * Support` on iOS. On iOS a database found in `Documents`, where an earlier default kept
         * it, is first moved to the default location with its files.
         *
         * When the directory has no database yet and the default location has one, the first client
         * moves it here, unsent writes included, and the downloaded files with it; when it cannot,
         * the default one stays in use and a warning is logged.
         *
         * On Android, keep its files out of Auto Backup, as [Offline] shows; on iOS the plugin
         * excludes them from backups itself.
         */
        public var dataDirectory: String? = null

        /**
         * The app's own connectivity signal, `true` while the device is online, in place of the
         * platform's monitor; `null`, the default, uses the monitor. The monitor is Android's
         * `ConnectivityManager` (a network with internet that Android has validated), which needs
         * the `ACCESS_NETWORK_STATE` permission the library's manifest declares, and Network's path
         * monitor on iOS. It starts with the client and stops with [close].
         *
         * The signal is a hint, never a gate: a request is tried whenever the app makes one,
         * whatever it says, because monitors are wrong both ways. It decides when the library tries
         * by itself: when it turns `true`, a reactive read's waiting refresh tries again at once
         * and, with [autoSync], queued writes are sent. While it is `false`, the library's own
         * passes stop after one fails for want of a connection, until it turns `true`, the session
         * settles anew or the app calls [syncPendingMutations], so a wrong signal costs one failed
         * attempt; a refresh that cannot reach the network reports [RefreshState.Paused]; and
         * [SyncStatus.network] says [NetworkStatus.OFFLINE]. A signal that fails or ends is no
         * longer watched (a failure is logged), and the network is then [NetworkStatus.UNKNOWN].
         */
        public var networkAvailability: Flow<Boolean>? = null

        /**
         * Whether the plugin sends queued writes by itself, without the app calling
         * [syncPendingMutations]. `true` by default.
         *
         * When `true`, it sends them when the session settles into a user with writes queued, when
         * the network comes back, and, for a client without Auth (whose session is settled from the
         * start), once when the client opens with writes an earlier run left queued. The first two
         * end at once any wait a failed pass left before the next, though never before a server's
         * `Retry-After` has passed. `false` stops all three; the app then calls
         * [syncPendingMutations] itself. Either way, a write queued behind an earlier one of the
         * same user is sent by itself once that one is, as is what a sent, refused or discarded
         * write leaves queued, and a retried rejection: a write made online would otherwise wait
         * for the app with nothing to show for it. While the monitor says offline, the library
         * makes one failed attempt by itself, then waits for the network, the session or the app.
         */
        public var autoSync: Boolean = true

        /**
         * Each table's primary-key columns, by table name: the name alone for a table in the
         * client's default schema, `schema.table` for one in another schema. A table not named here
         * has the key `id`.
         *
         * The key is how the cache recognizes a row: where a queued or applied upsert lands, which
         * row a server record replaces, and which rows a queued write reports in
         * [PendingMutation.rowKeys]. Set it for every table whose key is not `id`, such as one with
         * a composite key. Use [primaryKey] to add one.
         */
        public val primaryKeys: MutableMap<String, List<String>> = mutableMapOf()

        /**
         * Sets [table]'s primary key to [columns], in [primaryKeys].
         *
         * @param table the table: its name in the client's default schema, `schema.table` in
         *   another.
         * @param columns the key's columns, in any order; at least one.
         * @throws IllegalArgumentException when [columns] is empty.
         */
        public fun primaryKey(table: String, vararg columns: String) {
            require(columns.isNotEmpty()) { "A primary key needs at least one column" }
            primaryKeys[table] = columns.toList()
        }

        /**
         * Which responses from the server may replace what the cache holds;
         * [ResponseTrust.SettledSession] by default. See [ResponseTrust].
         */
        public var responseTrust: ResponseTrust = ResponseTrust.SettledSession

        /**
         * The least severe level the plugin logs at. Its messages go through supabase-kt's own
         * logging: to the logging processor the client was built with (`defaultLoggingFactory` in
         * `createSupabaseClient`), tagged `Supabase-Offline`. `null`, the default, uses the
         * client's `defaultLogLevel`, as supabase-kt's own plugins do. Messages at [LogLevel.INFO]
         * and above never carry rows, filter values or tokens; [LogLevel.DEBUG] may.
         */
        public var logLevel: LogLevel? = null

        /**
         * Each table's version column, by table name, keyed as [primaryKeys] is; none by default.
         * Use [versionColumn] to add one.
         *
         * A version column holds a value the server changes on every update of the row: a
         * `timestamptz` a trigger sets (such as `moddatetime(updated_at)`), or an integer a trigger
         * increments. Naming it guards the replay of a queued UPDATE or DELETE of one of the
         * table's rows: when the write's filter pins the row's key and the cache holds the row with
         * the column, the queue records the version the device saw, and a sync pass sends the write
         * only while the server's row still has it. A row that moved meanwhile is a conflict,
         * settled by [conflictResolver]; see [ConflictResolution] for the defaults.
         *
         * A write sent at once, an INSERT, an UPSERT, and an UPDATE or DELETE whose filter pins no
         * single row, whose filter itself names the version column, or whose row the cache does not
         * hold, stay unguarded: they are sent as they were made, and the last write wins. Without
         * the trigger on the server, a row that moved cannot be told from one that did not. The
         * value is compared as text, for equality only, exactly as the server sent it.
         */
        public val versionColumns: MutableMap<String, String> = mutableMapOf()

        /**
         * Sets [table]'s version column to [column], in [versionColumns].
         *
         * @param table the table: its name in the client's default schema, `schema.table` in
         *   another.
         * @param column the column; not blank, and not one of the table's key columns.
         * @throws IllegalArgumentException when [column] is blank.
         */
        public fun versionColumn(table: String, column: String) {
            require(column.isNotBlank()) { "A version column needs a name" }
            versionColumns[table] = column
        }

        /**
         * Settles the conflicts a sync pass finds when it replays a guarded write
         * ([versionColumns]); `null`, the default, settles them as [ConflictResolution] says. See
         * [ConflictResolver].
         */
        public var conflictResolver: ConflictResolver? = null

        /** Opens the plugin's local data: the platform's database, unless a test replaces it. */
        internal var openLocalData: (SupabaseClient, Config) -> OfflineClient = ::openPlatformClient

        /**
         * The tables `realtime { }` named: their changes on the server are applied to the cache as
         * they happen. None by default, and then no channel is opened.
         */
        public val realtimeTables: List<RealtimeTable>
            get() = realtimeTableList.toList()

        private val realtimeTableList: MutableList<RealtimeTable> = mutableListOf()

        /**
         * Names the tables whose changes on the server the plugin applies to the cache as they
         * happen, through supabase-kt's Realtime plugin, which must be installed; see
         * [RealtimeTables.table]. Each call adds to [realtimeTables].
         *
         * The channel is joined while a user is signed in (or [Auth] is not installed), the network
         * is not known down and [disableRealtime] has not switched it off, and left otherwise and
         * at [close]. Every join reads the named tables again, since Realtime replays nothing:
         * their live readers fetch again, and the next read of each cached query of theirs asks the
         * server. [realtimeStatus] says where the channel stands. An app without the Realtime
         * plugin that names a table here fails `createSupabaseClient` with [IllegalStateException].
         *
         * @param init the block naming the tables.
         * @throws IllegalArgumentException for a blank table, or one named twice for a schema.
         */
        public fun realtime(init: RealtimeTables.() -> Unit) {
            RealtimeTables(realtimeTableList).init()
        }

        /** [realtimeTables] as the engine follows them. */
        internal val watchedTables: List<WatchedTable>
            get() = realtimeTableList.map { named -> named.toWatchedTable() }

        /**
         * Makes the feed those changes arrive through, in place of Realtime's: a test's script.
         * `null` follows the Realtime plugin.
         */
        internal var serverChangeFeed: ((SupabaseClient) -> ServerChangeFeed)? = null
    }

    /** Installs the plugin: `install(Offline) { … }`. */
    public companion object : SupabasePluginProvider<Config, Offline> {

        /** The size that turns eviction off, for [Config.cacheSizeBytes] and its file twin. */
        public const val CACHE_SIZE_UNLIMITED: Long = -1L

        /** The key the plugin is installed under, `"offline"`. */
        override val key: String = "offline"

        /**
         * The configuration [init] writes.
         *
         * @throws IllegalArgumentException when a table's primary key names no column, or its
         *   version column is blank or one of its key columns.
         */
        override fun createConfig(init: Config.() -> Unit): Config =
            Config().apply(init).also { config ->
                config.primaryKeys.forEach { (table, columns) ->
                    require(columns.isNotEmpty()) { "The primary key of $table names no column" }
                }
                config.versionColumns.forEach { (table, column) ->
                    require(column.isNotBlank()) { "The version column of $table is blank" }
                    val keyColumns = config.primaryKeys[table] ?: DEFAULT_PRIMARY_KEY
                    require(column !in keyColumns) {
                        "The version column of $table is one of its key columns"
                    }
                }
            }

        /**
         * The plugin for [supabaseClient]. It opens its database once every plugin of the client
         * exists, in [SupabasePlugin.init].
         */
        override fun create(supabaseClient: SupabaseClient, config: Config): Offline =
            OfflinePlugin(supabaseClient, config)

        /** The default of [Config.cacheSizeBytes]: 100 MiB. */
        internal const val DEFAULT_CACHE_SIZE_BYTES: Long = 100L * 1024L * 1024L

        /** The default of [Config.fileCacheSizeBytes]: 256 MiB. */
        internal const val DEFAULT_FILE_CACHE_SIZE_BYTES: Long = 256L * 1024L * 1024L

        /** The least [Config.cacheSizeBytes] counts as: one SQLite page. */
        internal const val MIN_CACHE_SIZE_BYTES: Long = 4_096L
    }
}

/**
 * The [Offline] plugin installed in this client.
 *
 * @throws IllegalStateException when the client was built without `install(Offline)`.
 */
public val SupabaseClient.offline: Offline
    get() = pluginManager.getPlugin(Offline)

/**
 * Calls the database function [function] with [parameters], encoded by the client's PostgREST
 * serializer, on the server, as supabase-kt's `Postgrest.rpc` with a typed argument does. Network
 * only; see [Offline.rpc].
 *
 * @param function the function's name.
 * @param parameters the function's arguments, an object that encodes to a JSON object.
 * @param request the request: the HTTP method, a count, filters on the result.
 * @return the server's response.
 * @throws OfflineClientClosedException when the client is closed.
 * @throws IllegalStateException when [Postgrest] is not installed.
 */
@Throws(Throwable::class)
public suspend inline fun <reified T : Any> Offline.rpc(
    function: String,
    parameters: T,
    noinline request: RpcRequestBuilder.() -> Unit = {},
): PostgrestResult =
    rpc(
        function,
        supabaseClient.postgrest.serializer.encodeToJsonElement(parameters).jsonObject,
        request,
    )

/**
 * Applies a row the server reports to every cached query of [table], encoded by the client's
 * PostgREST serializer; see [Offline.applyServerRecord].
 *
 * @param table the table.
 * @param record the row, an object that encodes to a JSON object.
 * @param primaryKeys the columns that identify the row, or `null` for the table's primary key.
 * @param schema the table's schema, or `null` for the client's default schema.
 * @throws OfflineClientClosedException when the client is closed.
 */
@Throws(Throwable::class)
public suspend inline fun <reified T : Any> Offline.applyServerRecord(
    table: String,
    record: T,
    primaryKeys: List<String>? = null,
    schema: String? = null,
) {
    applyServerRecord(
        table,
        supabaseClient.postgrest.serializer.encodeToJsonElement(record).jsonObject,
        primaryKeys,
        schema,
    )
}
