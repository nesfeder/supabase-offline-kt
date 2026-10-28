// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.RealtimeTable
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.ServerRowChange
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.replayIdentityFor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeEvent
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeFeed
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeFollower
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The client's Realtime side: follows the watched tables' changes on the server for the identity
 * the cache belongs to ([ServerChangeFollower]), and applies them, and the records an app hands to
 * [OfflineClient.applyServerRecord] and [OfflineClient.applyServerDelete], to the cached queries
 * through one path, [CachePatcher.patchServerChanges]. A record that cannot be located by its
 * primary key is logged and left out.
 *
 * @param scope where the follow runs: the client's, so its close leaves the channel.
 * @param watchedTables the tables whose changes are followed; none follows nothing.
 * @param serverChangeFeed where those changes come from; `null` follows nothing.
 * @param networkStatuses the network as the client sees it, which pauses and resumes the follow.
 * @param wakeUps the edges that cut a stalled follow's backoff short.
 */
internal class RealtimeApplier(
    private val supabaseClient: SupabaseClient,
    scope: CoroutineScope,
    watchedTables: List<WatchedTable>,
    serverChangeFeed: ServerChangeFeed?,
    private val sessionIdentity: SessionIdentity,
    networkStatuses: Flow<NetworkStatus>,
    wakeUps: Flow<Unit>,
    private val operationGate: ClientOperationGate,
    private val primaryKeys: PrimaryKeys,
    private val cachePatcher: CachePatcher,
    private val freshness: CacheFreshness,
    private val engineLogger: EngineLogger,
) {

    /** Encodes the primary-key [FilterDescriptor] built for realtime records. */
    private val json = EngineJson

    /**
     * Follows the watched tables' changes on the server and applies them, in the client's scope so
     * its close leaves the channel; `null` when nothing is watched.
     */
    private val serverChangeFollower: ServerChangeFollower? =
        if (watchedTables.isEmpty() || serverChangeFeed == null) null
        else
            ServerChangeFollower(
                scope = scope,
                tables = watchedTables,
                feed = serverChangeFeed,
                defaultSchema = ::defaultSchema,
                sessionIdentity =
                    sessionIdentity.tokenIdentity?.state?.map { state ->
                        state.settled?.storedValue
                    }
                        ?: sessionIdentity
                            .authPluginOrNull()
                            ?.sessionStatus
                            ?.map(::followIdentityFor)
                        ?: flowOf(QueueIdentity.Anonymous.storedValue),
                currentIdentity = ::currentFollowIdentity,
                networkStatuses = networkStatuses,
                wakeUps = wakeUps,
                apply = ::applyServerChanges,
                markStale = ::markCachedQueriesStale,
                engineLogger = engineLogger,
            )

    /** The follow's state, [RealtimeStatus.Off] for good when nothing is watched. */
    val status: StateFlow<RealtimeStatus> =
        serverChangeFollower?.state ?: MutableStateFlow(RealtimeStatus.Off)

    /** Each watched table's state; none when nothing is watched. */
    val tableStatus: StateFlow<Map<RealtimeTable, RealtimeStatus>> =
        serverChangeFollower?.tableState ?: MutableStateFlow(emptyMap())

    /** Starts the follow, once at open. */
    fun start() {
        serverChangeFollower?.start()
    }

    /** Stops the follow, at close. */
    fun stop() {
        serverChangeFollower?.stop()
    }

    /** Turns the follow off or back on, as `disableRealtime` and `enableRealtime` ask. */
    fun setEnabled(enabled: Boolean) {
        serverChangeFollower?.setEnabled(enabled)
    }

    /** The PostgREST plugin's default schema, `public` without the plugin. */
    private fun defaultSchema(): String =
        supabaseClient.pluginManager.getPluginOrNull(Postgrest)?.config?.defaultSchema ?: "public"

    /**
     * The identity the channel is joined for under [status], the one the cache belongs to: `null`
     * while there is no user to subscribe for, so a change of user restarts the follow and a new
     * session of the same user does not. A session that names no user is followed as one identity
     * of its own; the cache is not served to it.
     */
    private fun followIdentityFor(status: SessionStatus): String? =
        if (status !is SessionStatus.Authenticated) null
        else replayIdentityFor(status)?.storedValue ?: UNNAMED_USER_FOLLOW_IDENTITY

    /** The identity the channel is joined for now; one for good without the Auth plugin. */
    private fun currentFollowIdentity(): String? {
        sessionIdentity.tokenIdentity?.let { source ->
            return source.state.value.settled?.storedValue
        }
        val auth = sessionIdentity.authPluginOrNull() ?: return QueueIdentity.Anonymous.storedValue
        return followIdentityFor(auth.sessionStatus.value)
    }

    /**
     * Applies the changes the feed reported to one table, in the order they arrived, as one patch,
     * through the path an app's [applyServerRecord] or [applyServerDelete] takes; a change whose
     * record cannot be located is logged and left out, as there. Nothing once the client is
     * closing, and nothing once the session is no longer [followedFor]'s, which the patch asks
     * under the table's lock: what an earlier user's channel still delivers never reaches the next
     * user's cache.
     *
     * @param followedFor the identity the channel was joined for.
     * @param changes at least one, all of one table and schema.
     */
    private suspend fun applyServerChanges(
        followedFor: String,
        changes: List<ServerChangeEvent.Change>,
    ) {
        if (operationGate.isClosed) return
        val first = changes.first()
        operationGate.call {
            val cachedTable = cachedTableOf(first.table, first.schema)
            val keyColumns = primaryKeys.of(cachedTable)
            val located = changes.mapNotNull { change ->
                when (change) {
                    is ServerChangeEvent.Saved ->
                        locateServerRow(
                            "applyServerRecord",
                            change.table,
                            change.record,
                            keyColumns,
                            deleted = false,
                        )
                    is ServerChangeEvent.Deleted ->
                        locateServerRow(
                            "applyServerDelete",
                            change.table,
                            change.oldRecord,
                            keyColumns,
                            deleted = true,
                        )
                }
            }
            if (located.isNotEmpty())
                cachePatcher.patchServerChanges(cachedTable, keyColumns, located) {
                    currentFollowIdentity() == followedFor
                }
        }
    }

    /**
     * Marks every cached query of [table] in [schema] stale and has its live readers fetch again:
     * what a join asks for, since Realtime replays nothing. Nothing once the client is closing.
     */
    private suspend fun markCachedQueriesStale(table: String, schema: String) {
        if (operationGate.isClosed) return
        operationGate.call {
            cachePatcher.markEveryQueryStale(cachedTableOf(table, schema), freshness.tickNow())
        }
    }

    /**
     * Applies [record], the app's own, to the cached queries of [table] in [schema], located by
     * [primaryKeys] or the table's configured key; the caller holds the client's gate.
     */
    suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>?,
        schema: String?,
    ) {
        val cachedTable = cachedTableOf(table, schema)
        val keyColumns = primaryKeys ?: this.primaryKeys.of(cachedTable)
        val change =
            locateServerRow("applyServerRecord", table, record, keyColumns, deleted = false)
                ?: return
        cachePatcher.patchServerChanges(cachedTable, keyColumns, listOf(change))
    }

    /**
     * Removes the row [oldRecord] names, the app's own, from the cached queries of [table] in
     * [schema], located as [applyServerRecord] locates it; the caller holds the client's gate.
     */
    suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String>?,
        schema: String?,
    ) {
        val cachedTable = cachedTableOf(table, schema)
        val keyColumns = primaryKeys ?: this.primaryKeys.of(cachedTable)
        val change =
            locateServerRow("applyServerDelete", table, oldRecord, keyColumns, deleted = true)
                ?: return
        cachePatcher.patchServerChanges(cachedTable, keyColumns, listOf(change))
    }

    /**
     * [record] as [CachePatcher.patchServerChanges] takes it, located by [keyColumns]; `null`,
     * logged under [call] without the record, when a key column is absent or non-primitive, so the
     * caller drops an unlocatable record instead of patching the wrong rows.
     */
    private fun locateServerRow(
        call: String,
        table: String,
        record: JsonObject,
        keyColumns: List<String>,
        deleted: Boolean,
    ): ServerRowChange? {
        val filterJson = primaryKeyFilterJson(record, keyColumns)
        if (filterJson == null) {
            engineLogger.warning {
                "$call($table): dropping record — primary key(s) $keyColumns absent or non-primitive"
            }
            return null
        }
        return ServerRowChange(record, filterJson, deleted)
    }

    /**
     * The name [table] of [schema] is cached under: its own in the default schema, `schema.table`
     * in another, as a read of it caches it.
     */
    private fun cachedTableOf(table: String, schema: String?): String {
        val defaultSchema =
            supabaseClient.pluginManager.getPluginOrNull(Postgrest)?.config?.defaultSchema
        return if (schema.isNullOrBlank() || schema == defaultSchema) table else "$schema.$table"
    }

    /**
     * Builds the encoded [FilterDescriptor] that locates [record]'s row by its [primaryKeys], as an
     * AND of `eq` conditions — mirroring the on-conflict filter synthesis the overlay uses for
     * online upserts. Returns `null` when [primaryKeys] is empty, or any key is absent from
     * [record] or maps to a non-primitive (null / array / object) value, so the caller can drop an
     * unlocatable record instead of patching the wrong rows.
     */
    private fun primaryKeyFilterJson(record: JsonObject, primaryKeys: List<String>): String? {
        if (primaryKeys.isEmpty()) return null
        val conditions = primaryKeys.map { column ->
            val value = record[column]
            if (value !is JsonPrimitive || value is JsonNull) return null
            FilterDescriptor.Condition(
                column = column,
                operatorId = "eq",
                value = value.content,
            )
        }
        val descriptor =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions = conditions,
            )
        return encodeFilter(descriptor, json)
    }

    private companion object {
        /** The identity the channel is joined for under a session that names no user. */
        const val UNNAMED_USER_FOLLOW_IDENTITY: String = "user:"
    }
}
