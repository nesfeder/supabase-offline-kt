// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseInternal::class, ExperimentalCoroutinesApi::class, SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingSupabaseLogs
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Pins the upstream behavior the read-shape check depends on: supabase-kt writes the shape a SELECT
 * asks for only into its method and its `Accept` and `Prefer` headers. Also pins that the check's
 * own PostgREST instance builds the request the app's instance would, that the check sends nothing
 * and stops every request made under it and none made outside it, and that its token never leaves.
 */
class ReadShapeProbeContractTest {

    private val server = RecordingPostgrestServer()
    private val handle = SupabaseRemoteTable(server.supabaseClient().postgrest.from("todos"))

    @Test
    fun each_read_shape_is_told_without_sending_the_read() = runTest {
        // Given
        val reads: List<Pair<ReadShape, SelectRequestBuilder.() -> Unit>> =
            listOf(
                ReadShape.RowList to { filter { eq("id", 1) } },
                ReadShape.RowList to
                    {
                        filter { eq("id", 1) }
                        order("id", Order.ASCENDING)
                        limit(5)
                    },
                ReadShape.Uncacheable("a single object (single(), maybeSingle())") to { single() },
                ReadShape.Uncacheable("a single object (single(), maybeSingle())") to
                    {
                        maybeSingle()
                    },
                ReadShape.Uncacheable("a HEAD request") to { head = true },
                ReadShape.Uncacheable("a read with a row count") to { count(Count.EXACT) },
                ReadShape.Uncacheable("another media type (csv(), geojson())") to { csv() },
                ReadShape.Uncacheable("another media type (csv(), geojson())") to { geojson() },
                ReadShape.Uncacheable("rows with nulls stripped (stripNulls())") to
                    {
                        stripNulls()
                    },
                ReadShape.Uncacheable("a query plan (explain())") to { explain() },
                ReadShape.Uncacheable("a single object (single(), maybeSingle())") to
                    {
                        headers.append("Accept", "application/vnd.pgrst.object+json")
                    },
            )

        // When
        val shapes = reads.map { (_, request) -> handle.shapeOf(Columns.ALL, request) }

        // Then — each told apart, and nothing reached the server
        assertEquals(reads.map { (expected, _) -> expected }, shapes)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun a_request_made_outside_the_check_passes_it() = runTest {
        // Given — the check installed, and requests made with no capture
        val supabase = server.supabaseClient()
        supabase.installReadShapeCheck()

        // When
        supabase.httpClient.httpClient.get("$TEST_SUPABASE_URL/auth/v1/user")
        supabase.postgrest.from("todos").select { filter { eq("id", 1) } }
        supabase.postgrest.from("todos").update(Json.parseToJsonElement("""{"title":"x"}""")) {
            filter { eq("id", 1) }
        }

        // Then — each reached the server
        assertEquals(
            listOf("user", "todos", "todos"),
            server.requests.map { request -> request.table },
        )
    }

    @Test
    fun every_request_made_under_the_check_is_stopped_whatever_its_url() = runTest {
        // Given
        val supabase = server.supabaseClient()
        supabase.installReadShapeCheck()

        // When — another service's read, a write, and a read under another host
        listOf<suspend () -> Unit>(
                { supabase.httpClient.httpClient.get("$TEST_SUPABASE_URL/auth/v1/user") },
                {
                    supabase.postgrest.from("todos").update(
                        Json.parseToJsonElement("""{"title":"x"}""")
                    ) {
                        filter { eq("id", 1) }
                    }
                },
                { supabase.httpClient.httpClient.get("https://elsewhere.invalid/rest/v1/todos") },
            )
            .forEach { send ->
                val capture = ReadRequestCapture()
                runCatching { withContext(capture) { send() } }
                assertNotNull(capture.url)
            }

        // Then — none reached the server
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun the_checks_own_instance_builds_the_request_the_apps_instance_would() = runTest {
        // Given — an app that configures every PostgREST setting a request is built from
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) {
                    defaultSchema = "app"
                    propertyConversionMethod = PropertyConversionMethod.NONE
                    customUrl = "$TEST_SUPABASE_URL/custom/rest/v1"
                    serializer = KotlinXSerializer(Json { encodeDefaults = true })
                    urlLengthLimit = 4_000
                    timeout = 12.seconds
                }
            }
        supabase.installReadShapeCheck()
        val check = readShapePostgrestOf(supabase.postgrest)
        val requests =
            listOf<SelectRequestBuilder.() -> Unit>(
                {
                    filter {
                        eq("listId", "A")
                        isIn("tag", listOf("x,y", "z"))
                    }
                    order("position", Order.DESCENDING)
                    limit(20)
                },
                {
                    schema = "public"
                    single()
                },
                {
                    head = true
                    count(Count.EXACT)
                },
                {
                    csv()
                    range(0, 9)
                },
                {
                    headers.append("X-App", "yes")
                    params["custom"] = listOf("1")
                },
            )

        requests.forEach { request ->
            // When — the same read built by each instance, stopped before it is sent
            val viaApp = captureOf {
                supabase.postgrest.from("todos").select(Columns.raw("id,listId")) {
                    request()
                    noRetry()
                }
            }
            val viaCheck = captureOf {
                check.from("app", "todos").select(Columns.raw("id,listId")) {
                    request()
                    noRetry()
                }
            }

            // Then — the same URL and headers, but for the token
            assertEquals(viaApp.url, viaCheck.url)
            assertEquals(
                viaApp.headers - HttpHeaders.Authorization,
                viaCheck.headers - HttpHeaders.Authorization,
            )
            assertEquals(viaApp.shape, viaCheck.shape)
        }
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun the_checks_own_instance_copies_each_setting_of_the_apps_but_the_token_and_retries() {
        // Given — every setting away from its default, a logging factory of the app's own included
        val appsLogs = RecordingSupabaseLogs()
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) {
                    defaultSchema = "app"
                    propertyConversionMethod = PropertyConversionMethod.NONE
                    requireValidSession = true
                    urlLengthLimit = 4_000
                    timeout = 12.seconds
                    maxRetries = 5
                    customUrl = "$TEST_SUPABASE_URL/custom/rest/v1"
                    serializer = KotlinXSerializer(Json { encodeDefaults = true })
                    logLevel = LogLevel.WARNING
                    loggingFactory = { appsLogs }
                    jwtToken = "the-apps-own-token"
                }
            }
        val source = supabase.postgrest.config

        // When
        val copied = readShapePostgrestOf(supabase.postgrest).config

        // Then — the constructor's settings, then those declared outside it, one by one
        assertEquals(source.copy(maxRetries = 0), copied)
        assertEquals(source.customUrl, copied.customUrl)
        assertEquals(source.serializer, copied.serializer)
        assertEquals(source.logLevel, copied.logLevel)
        assertNotNull(source.loggingFactory)
        assertEquals(source.loggingFactory, copied.loggingFactory)
        assertEquals(READ_SHAPE_CHECK_TOKEN, copied.jwtToken)
        assertEquals(0, copied.maxRetries)
    }

    @Test
    fun the_checks_token_never_leaves_the_process() = runTest {
        // Given — every supabase-kt log line recorded
        val logs = RecordingSupabaseLogs()
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                defaultLogLevel = LogLevel.DEBUG
                defaultLoggingFactory = { logs }
                install(Postgrest)
            }
        supabase.installReadShapeCheck()
        val probe =
            PipelineReadShapeProbe(readShapePostgrestOf(supabase.postgrest), "todos", "public")

        // When
        repeat(50) { index -> probe.shapeOf(Columns.ALL, request = { filter { eq("id", index) } }) }

        // Then — nothing sent, and the token in no line supabase-kt logged
        assertEquals(emptyList(), server.requests)
        assertTrue(logs.entries.none { (_, line) -> READ_SHAPE_CHECK_TOKEN in line })
    }

    @Test
    fun a_read_whose_request_cannot_be_built_fails_with_the_builders_own_error() = runTest {
        // Given — a URL limit no read fits
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                install(Postgrest) { urlLengthLimit = 10 }
            }
        supabase.installReadShapeCheck()
        val probe =
            PipelineReadShapeProbe(readShapePostgrestOf(supabase.postgrest), "todos", "public")

        // When
        val failure = runCatching {
            probe.shapeOf(Columns.ALL, request = { filter { eq("id", 1) } })
        }
            .exceptionOrNull()

        // Then — not taken for a list: the error building it raised
        assertTrue(failure?.message.orEmpty().contains("URL length"), "$failure")
        assertEquals(emptyList(), server.requests)
    }

    /** What the check's phase recorded of the request [send] builds. */
    private suspend fun captureOf(send: suspend () -> Unit): ReadRequestCapture {
        val capture = ReadRequestCapture()
        runCatching { withContext(capture) { send() } }
        return capture
    }

    @Test
    fun the_check_is_installed_once_however_many_callers_race_to_install_it() = runTest {
        // Given — a client nothing has installed the check on yet
        val supabase = server.supabaseClient()

        // When — many first uses at once, released together
        val start = CompletableDeferred<Unit>()
        val racers =
            (1..64).map {
                async(Dispatchers.Default) {
                    start.await()
                    supabase.installReadShapeCheck()
                }
            }
        start.complete(Unit)
        val installs = racers.awaitAll()

        // Then — exactly one of them put the interceptor on the pipeline
        assertEquals(1, installs.count { installed -> installed })
    }

    @Test
    fun an_apps_retry_plugin_does_not_retry_the_check() = runTest {
        // Given — an app that retries every failed request three times, 400 ms apart, and a list
        // the cache holds; the server answers on the test scheduler, so only a wait moves the
        // test's clock
        val onTheTestClock =
            RecordingPostgrestServer(
                answer = { okJson("""[{"id":1}]""") },
                engineDispatcher = StandardTestDispatcher(testScheduler),
            )
        val supabase =
            newTestSupabaseClient(engine = onTheTestClock.engine) {
                install(Postgrest) { maxRetries = 0 }
                httpConfig {
                    install(HttpRequestRetry) {
                        retryOnException(maxRetries = 3, retryOnTimeout = true)
                        constantDelay(millis = 400)
                    }
                }
            }
        val builder =
            OfflinePostgrestQueryBuilderImpl(
                delegate = supabase.postgrest.from("todos"),
                store = newTestOfflineStore(),
            )
        builder.select(readStrategy = ReadStrategy.NetworkOnly)

        // When — a read the cache answers, timed on the test's clock
        val startedAt = testScheduler.currentTime
        builder.select(readStrategy = ReadStrategy.CacheOnly())
        val waited = (testScheduler.currentTime - startedAt).milliseconds

        // Then — the check that stops before the network was never retried, and sent nothing
        assertTrue(waited < 400.milliseconds, "the read waited $waited for retries")
        assertEquals(1, onTheTestClock.requests.size)
    }
}
