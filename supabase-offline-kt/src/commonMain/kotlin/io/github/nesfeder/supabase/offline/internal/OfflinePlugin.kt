// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.request.RpcRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder
import io.github.nesfeder.supabase.offline.OfflineStorage
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.internal.logging.engineLogger
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The [Offline] plugin as `install(Offline)` creates it. supabase-kt creates every plugin before
 * any exists to be looked up, then calls [init] on each; the plugin opens its [OfflineClient]
 * there, once Auth and PostgREST can be found, and forwards every call to it.
 */
@OptIn(OfflineInternal::class, ExperimentalAtomicApi::class)
internal class OfflinePlugin(
    override val supabaseClient: SupabaseClient,
    override val config: Offline.Config,
) : Offline {

    /** The client [init] opened; `null` before it ran. */
    private val opened = AtomicReference<OfflineClient?>(null)

    /** The opened client. */
    private val client: OfflineClient
        get() =
            checkNotNull(opened.load()) {
                "The Offline plugin is used before its SupabaseClient finished initializing it"
            }

    /**
     * Opens the client's database: every plugin of [supabaseClient] exists by now. Then warns,
     * once, when the app resolved another supabase-kt minor than the one this library was tested
     * with.
     */
    override fun init() {
        val client = config.openLocalData(supabaseClient, config)
        if (!opened.compareAndSet(null, client)) error("The Offline plugin was initialized twice")
        supabaseClient.warnUnlessTestedSupabaseKt(supabaseClient.engineLogger(config.logLevel))
    }

    override fun from(table: String): OfflinePostgrestQueryBuilder = client.from(table)

    override fun from(schema: String, table: String): OfflinePostgrestQueryBuilder =
        client.from(schema, table)

    override suspend fun rpc(
        function: String,
        request: RpcRequestBuilder.() -> Unit,
    ): PostgrestResult = client.rpc(function, request)

    override suspend fun rpc(
        function: String,
        parameters: JsonObject,
        request: RpcRequestBuilder.() -> Unit,
    ): PostgrestResult = client.rpc(function, parameters, request)

    override val storage: OfflineStorage
        get() = client.storage

    override fun pendingMutations(table: String?): Flow<List<PendingMutation>> =
        client.pendingMutations(table)

    override suspend fun pendingMutationCount(table: String?): Int =
        client.pendingMutationCount(table)

    override fun pendingMutationCountFlow(table: String?): Flow<Int> =
        client.pendingMutationCountFlow(table)

    override suspend fun discardPendingMutation(id: Long): Unit = client.discardPendingMutation(id)

    override fun rejectedMutations(): Flow<List<RejectedMutation>> = client.rejectedMutations()

    override suspend fun dismissRejectedMutation(id: Long): Unit =
        client.dismissRejectedMutation(id)

    override suspend fun retryRejectedMutation(id: Long): Unit = client.retryRejectedMutation(id)

    override suspend fun syncPendingMutations(table: String?): SyncReport =
        client.syncPendingMutations(table)

    override val syncStatus: StateFlow<SyncStatus>
        get() = client.syncStatus

    override suspend fun disableNetwork(): Unit = client.disableNetwork()

    override suspend fun enableNetwork(): Unit = client.enableNetwork()

    override suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>?,
        schema: String?,
    ): Unit = client.applyServerRecord(table, record, primaryKeys, schema)

    override suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String>?,
        schema: String?,
    ): Unit = client.applyServerDelete(table, oldRecord, primaryKeys, schema)

    override suspend fun clearLocalData(): Unit = client.clearLocalData()

    /** Closes the client; nothing to close when [init] never ran or failed. */
    override suspend fun close() {
        opened.load()?.close()
    }
}
