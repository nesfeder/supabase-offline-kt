@file:OptIn(SupabaseInternal::class)

package io.github.nesfeder.supabase.internal

import io.github.jan.supabase.AccessTokenProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseSerializer
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.network.KtorSupabaseHttpClient
import io.github.jan.supabase.plugins.PluginManager
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import io.github.jan.supabase.postgrest.query.request.RpcRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.Storage
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KType

/**
 * Trivial SupabaseSerializer for tests.
 * All methods just throw; we only need an instance for wiring.
 */
class FakeSupabaseSerializer : SupabaseSerializer {

    override fun <T : Any> encode(type: KType, value: T): String {
        throw UnsupportedOperationException("FakeSupabaseSerializer.encode not used in tests")
    }

    override fun <T : Any> decode(type: KType, value: String): T {
        throw UnsupportedOperationException("FakeSupabaseSerializer.decode not used in tests")
    }
}

/**
 * Simple fake SupabaseClient that wires in a FakePluginManager + your Postgrest fake.
 *
 * Only the pieces used by OfflineMutationSyncer (postgrest + config.defaultSchema)
 * are really important; everything else can be stubbed / throw.
 *
 * Tests may also use [registerBuilder] helpers to register table-specific
 * [PostgrestQueryBuilder] instances on the underlying [FakePostgrest].
 */
class FakeSupabaseClient(
    private val postgrestPlugin: Postgrest? = null,
    private val storagePlugin: Storage? = null,
    override val supabaseHttpUrl: String = "https://test.supabase.co",
    override val supabaseUrl: String = "test.supabase.co",
    override val supabaseKey: String = "test-key",
    override val useHTTPS: Boolean = true,
    override val defaultSerializer: SupabaseSerializer = FakeSupabaseSerializer(),
    override val accessToken: AccessTokenProvider? = null,
    override val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
) : SupabaseClient {

    override val pluginManager: PluginManager = PluginManager(
        buildMap {
            postgrestPlugin?.let { put("rest", it) }
            storagePlugin?.let { put("storage", it) }
        }
    )

    /**
     * Convenience accessor for the underlying [FakePostgrest] when tests need
     * to configure table builders directly.
     */
    private val fakePostgrest: FakePostgrest?
        get() = postgrestPlugin as? FakePostgrest

    /**
     * Register a [PostgrestQueryBuilder] for the given [table] in the default schema.
     *
     * This simply delegates to [FakePostgrest.registerBuilder]. It will throw if
     * this client was not constructed with a [FakePostgrest] instance.
     */
    fun registerBuilder(table: String, builder: PostgrestQueryBuilder) {
        val fake = fakePostgrest
            ?: error("FakeSupabaseClient.registerBuilder requires FakePostgrest as postgrest plugin")
        fake.registerBuilder(table, builder)
    }

    /**
     * Register a [PostgrestQueryBuilder] for the given [schema] and [table].
     */
    fun registerBuilder(schema: String, table: String, builder: PostgrestQueryBuilder) {
        val fake = fakePostgrest
            ?: error("FakeSupabaseClient.registerBuilder requires FakePostgrest as postgrest plugin")
        fake.registerBuilder(schema, table, builder)
    }

    override val httpClient: KtorSupabaseHttpClient
        get() = error("FakeSupabaseClient.httpClient is not used in tests")

    override suspend fun close() {
        // No-op in tests
    }
}

/**
 * Very small fake Postgrest implementation.
 *
 * You can register builders per table / schema, and OfflineMutationSyncer will call:
 *   postgrest.from(schema, table)
 * which we resolve using an internal map.
 *
 * In tests we don't need a real [SupabaseClient] backing this plugin – consumers only
 * access [config] and the [from] lookups – so [supabaseClient] simply throws if used.
 *
 * Additionally, tests may provide an optional [throwException] callback that is invoked
 * whenever either `from(table)` or `from(schema, table)` is called. The table name is
 * passed to the callback so that callers can selectively throw for certain tables
 * (e.g. to simulate connectivity issues for just one table).
 */
class FakePostgrest(
    override val config: Postgrest.Config = Postgrest.Config(),
    /**
     * Optional hook to force `from(...)` calls to throw. If this returns a non-null
     * [Throwable] for the given table, that throwable will be thrown instead of
     * returning a [PostgrestQueryBuilder].
     */
    var throwException: ((table: String) -> Throwable?)? = null,
) : Postgrest {

    override val supabaseClient: SupabaseClient
        get() = error("FakePostgrest.supabaseClient is not used in tests")

    /**
     * Table / schema → PostgrestQueryBuilder mapping.
     * Key form:
     *   - "table"
     *   - "schema.table"
     */
    private val builders: MutableMap<String, PostgrestQueryBuilder> = mutableMapOf()

    /**
     * Register a builder for the default-schema table (e.g. "assets").
     */
    fun registerBuilder(table: String, builder: PostgrestQueryBuilder) {
        builders[tableKey(table)] = builder
    }

    /**
     * Register a builder for (schema, table), e.g. ("public", "assets").
     */
    fun registerBuilder(schema: String, table: String, builder: PostgrestQueryBuilder) {
        builders[tableKey(schema, table)] = builder
    }

    private fun tableKey(table: String): String = table
    private fun tableKey(schema: String, table: String): String = "$schema.$table"

    override fun from(table: String): PostgrestQueryBuilder {
        // Allow tests to force `from` to fail for specific tables.
        throwException?.invoke(table)?.let { throw it }

        val key = tableKey(table)
        return builders[key]
            ?: error("FakePostgrest: no PostgrestQueryBuilder registered for table '$table'")
    }

    override fun from(schema: String, table: String): PostgrestQueryBuilder {
        // Allow tests to force `from` to fail for specific tables.
        throwException?.invoke(table)?.let { throw it }

        val schemaKey = tableKey(schema, table)
        val tableKey = tableKey(table)
        return builders[schemaKey]
            ?: builders[tableKey]
            ?: error("FakePostgrest: no PostgrestQueryBuilder registered for '$schema.$table'")
    }

    override suspend fun rpc(
        function: String,
        request: RpcRequestBuilder.() -> Unit
    ): PostgrestResult {
        throw UnsupportedOperationException("FakePostgrest.rpc(function) not implemented for tests")
    }

    override suspend fun rpc(
        function: String,
        parameters: JsonObject,
        request: RpcRequestBuilder.() -> Unit
    ): PostgrestResult {
        throw UnsupportedOperationException("FakePostgrest.rpc(function, parameters) not implemented for tests")
    }

    override val apiVersion: Int
        get() = 1

    override val pluginKey: String
        get() = "rest"

    override suspend fun parseErrorResponse(response: HttpResponse): RestException {
        return RestException("stubbed", "desc", response)
    }

    override val serializer: SupabaseSerializer
        get() = FakeSupabaseSerializer()
}