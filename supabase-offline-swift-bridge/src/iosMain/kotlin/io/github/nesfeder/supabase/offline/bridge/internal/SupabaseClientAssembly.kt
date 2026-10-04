// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.AccessTokenProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.bridge.BridgeAccessTokenCallback
import io.github.nesfeder.supabase.offline.bridge.BridgeAccessTokenProvider
import io.github.nesfeder.supabase.offline.bridge.BridgeClientOptions
import io.github.nesfeder.supabase.offline.bridge.BridgeLogLevel
import io.ktor.client.engine.HttpClientEngine
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The supabase-kt client a bridge client wraps: PostgREST, Storage and the offline plugin, in that
 * order, the plugin last. No Auth plugin: the app's session reaches requests through
 * [BridgeClientOptions.accessTokenProvider].
 *
 * @param engine the HTTP engine: Darwin in production, the fake server's in tests.
 * @param networkAvailability the app's connectivity reports, or `null` for the library's monitor.
 * @param postgrestRetries PostgREST's retry count, or `null` for supabase-kt's default.
 * @param configureOffline the last settings of the plugin, such as keeping its data in memory.
 */
internal fun assembleSupabaseClient(
    options: BridgeClientOptions,
    engine: HttpClientEngine,
    networkAvailability: Flow<Boolean>?,
    postgrestRetries: Int? = null,
    configureOffline: Offline.Config.() -> Unit = {},
): SupabaseClient =
    createSupabaseClient(supabaseUrl = options.supabaseUrl, supabaseKey = options.supabaseKey) {
        httpEngine = engine
        defaultLogLevel = options.logLevel.toSupabaseLevel()
        val sink = options.logSink
        defaultLoggingFactory = { level -> SinkLoggingProcessor(sink, level.toBridgeLevel()) }
        options.accessTokenProvider?.let { provider ->
            accessToken = accessTokenFrom(provider, options)
        }
        install(Postgrest) {
            defaultSchema = options.schema
            postgrestRetries?.let { maxRetries = it }
        }
        // Resumable uploads are not bridged; supabase-kt's default cache would keep their state
        // in the app's user defaults.
        install(Storage) { resumable { cache = MemoryResumableCache() } }
        install(Offline) {
            dataDirectory = options.dataDirectory
            cacheSizeBytes = options.cacheSizeBytes
            fileCacheSizeBytes = options.fileCacheSizeBytes
            autoSync = options.autoSync
            this.networkAvailability = networkAvailability
            primaryKeys.putAll(options.primaryKeys)
            configureOffline()
        }
    }

/**
 * supabase-kt's token provider over the app's callback: each request waits for one answer. A second
 * answer, or one after the request was cancelled, is dropped.
 */
private fun accessTokenFrom(
    provider: BridgeAccessTokenProvider,
    options: BridgeClientOptions,
): AccessTokenProvider = {
    suspendCancellableCoroutine { continuation ->
        val answered = AtomicBoolean(false)
        val callback = BridgeAccessTokenCallback { token, failure ->
            try {
                if (answered.compareAndSet(expectedValue = false, newValue = true)) {
                    if (failure == null) continuation.resume(token)
                    else continuation.resumeWithException(AccessTokenException(failure))
                }
            } catch (unexpected: Throwable) {
                logSafely(
                    options.logSink,
                    BridgeLogLevel.ERROR,
                    BRIDGE_LOG_TAG,
                    "An access-token answer failed (${unexpected::class.simpleName})",
                )
            }
        }
        provider.fetchAccessToken(callback)
    }
}
