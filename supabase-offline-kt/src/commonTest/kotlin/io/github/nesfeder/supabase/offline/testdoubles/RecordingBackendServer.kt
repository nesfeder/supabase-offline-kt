// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.IOException

/**
 * One server answering PostgREST and Storage, for tests that drive a whole client: every reply and
 * every stored object carries [marker], and it counts what reaches it.
 *
 * @property marker the string every row and object it serves contains.
 */
internal class RecordingBackendServer(val marker: String) {

    /** When `false`, every request fails at the transport layer. */
    val online: AtomicBoolean = AtomicBoolean(true)

    /** PostgREST writes received. */
    val restWrites: AtomicInt = AtomicInt(0)

    /** PostgREST requests received, as a flow a test can wait on. */
    val restArrived: MutableStateFlow<Int> = MutableStateFlow(0)

    /** Storage downloads received. */
    val storageArrived: MutableStateFlow<Int> = MutableStateFlow(0)

    /** Every request received, PostgREST and Storage together. */
    fun requestsArrived(): Int = restArrived.value + storageArrived.value

    /** When set, every PostgREST request waits for it before it is answered. */
    val restHold: MutableStateFlow<CompletableDeferred<Unit>?> = MutableStateFlow(null)

    /** The engine answering both. */
    val engine: MockEngine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        when {
            request.url.encodedPath.startsWith("/storage/v1/object/authenticated/") -> {
                storageArrived.update { count -> count + 1 }
                if (!online.load()) throw IOException("network unreachable")
                respond(
                    content = "an avatar for $marker".encodeToByteArray(),
                    status = HttpStatusCode.OK,
                    headers =
                        headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.OctetStream.toString(),
                        ),
                )
            }
            request.url.encodedPath.startsWith("/rest/v1/") -> {
                if (!online.load()) throw IOException("network unreachable")
                if (request.method != HttpMethod.Get) restWrites.addAndFetch(1)
                restArrived.update { count -> count + 1 }
                restHold.value?.await()
                if (request.method == HttpMethod.Get) {
                    respond(
                        """[{"id":1,"email":"$marker@example.com","bio":"${"lorem ".repeat(40)}"}]""",
                        HttpStatusCode.OK,
                        json,
                    )
                } else {
                    respond("[]", HttpStatusCode.OK, json)
                }
            }
            else -> error("RecordingBackendServer has no route for ${request.url.encodedPath}")
        }
    }

    /** A supabase-kt client with PostgREST and Storage whose requests reach this server. */
    fun supabaseClient(): SupabaseClient =
        newTestSupabaseClient(engine = engine) {
            install(Postgrest) { maxRetries = 0 }
            install(Storage) { resumable { cache = FakeResumableCache() } }
        }
}
