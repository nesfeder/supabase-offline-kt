// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.bridge.internal.ILLEGAL_HEADER_MESSAGE
import io.github.nesfeder.supabase.offline.bridge.internal.OperationRunner
import io.github.nesfeder.supabase.offline.bridge.internal.transportReason
import io.github.nesfeder.supabase.offline.offline
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/** How each kind of failure arrives: as one error value with the fields its kind promises. */
class BridgeErrorsTest {

    private val server = RecordingServer()

    @Test
    fun a_postgrest_refusal_carries_its_status_code_details_and_hint() = bridgeTest {
        // Given — a server that refuses the insert as PostgREST does
        server.answer = {
            ScriptedAnswer.json(
                409,
                """{"code":"23505","message":"duplicate key value","details":"Key (id)=(1) already exists.","hint":"Use another id"}""",
            )
        }
        val client = openTestClient(server)

        // When — a write that must reach the server now
        val (_, error) =
            client.mutateAndWait(
                """{"v":1,"operation":"insert","table":"todos","strategy":"onlineOnly"}""",
                """{"id":1}""",
            )

        // Then
        assertEquals(BridgeErrorKind.SERVER, error?.kind)
        assertEquals(409, error?.httpStatus)
        assertEquals("23505", error?.code)
        assertEquals("duplicate key value", error?.message)
        assertEquals("Key (id)=(1) already exists.", error?.details)
        assertEquals("Use another id", error?.hint)
        assertEquals(false, error?.isConnectivityFailure)
        client.closeAndWait()
    }

    @Test
    fun a_storage_refusal_carries_its_status_and_never_the_url() = bridgeTest {
        // Given — a server with no such object
        server.answer = {
            ScriptedAnswer.json(
                400,
                """{"statusCode":"404","error":"not_found","message":"Object not found"}""",
            )
        }
        val client = openTestClient(server)

        // When
        val probe = CompletionProbe<NSData>()
        client.storage.download("avatars", "secret-user/photo.png", false, null, probe.completion)
        val error = probe.awaitError()

        // Then
        assertEquals(BridgeErrorKind.HTTP, error.kind)
        assertEquals(400, error.httpStatus)
        assertFalse(error.message.contains("secret-user"))
        assertFalse(error.details.orEmpty().contains("fixture.supabase.co"))
        client.closeAndWait()
    }

    @Test
    fun a_storage_refusal_carries_storages_code_and_message_and_nothing_else() = bridgeTest {
        // Given — Storage's own refusals, and a gateway page that is not Storage's
        val refusals =
            listOf(
                Triple(403, "Unauthorized", "new row violates row-level security policy"),
                Triple(404, "not_found", "Object not found"),
                Triple(409, "Duplicate", "The resource already exists"),
            )
        val client = openTestClient(server)

        for ((status, code, message) in refusals) {
            server.answer = {
                ScriptedAnswer.json(
                    status,
                    """{"statusCode":"$status","error":"$code","message":"$message"}""",
                )
            }

            // When
            val probe = CompletionProbe<NSData>()
            client.storage.download("avatars", "a.png", false, null, probe.completion)
            val error = probe.awaitError()

            // Then
            assertEquals(BridgeErrorKind.HTTP, error.kind)
            assertEquals(status, error.httpStatus)
            assertEquals(code, error.code)
            assertEquals(message, error.message)
            assertEquals(null, error.details)
        }
        server.answer = {
            ScriptedAnswer(502, "<html>Bad gateway</html>".encodeToByteArray(), "text/html")
        }
        val probe = CompletionProbe<NSData>()
        client.storage.download("avatars", "a.png", false, null, probe.completion)
        val gateway = probe.awaitError()
        assertEquals(502, gateway.httpStatus)
        assertEquals(null, gateway.code)
        assertEquals("The server answered 502", gateway.message)
        client.closeAndWait()
    }

    @Test
    fun a_decoding_failure_says_where_and_names_no_type_or_payload() = bridgeTest {
        // Given — a listing missing a field, and a batch of signed URLs with a null one
        server.answer = { request ->
            if (request.path.startsWith("/storage/v1/object/list/")) {
                ScriptedAnswer.json(200, """[{"id":"secret-object"}]""")
            } else {
                ScriptedAnswer.json(
                    200,
                    """[{"error":"Either the object does not exist","signedURL":null,"path":"secret-path.png"}]""",
                )
            }
        }
        val client = openTestClient(server)

        // When
        val listing = CompletionProbe<String>()
        client.storage.list("avatars", "", null, listing.completion)
        val signing = CompletionProbe<String>()
        client.storage.createSignedUrls(
            "avatars",
            listOf("secret-path.png"),
            60,
            signing.completion,
        )
        val errors = listOf(listing.awaitError(), signing.awaitError())

        // Then
        for (error in errors) {
            assertEquals(BridgeErrorKind.DECODING, error.kind)
            assertTrue(error.message.startsWith("The server's answer could not be read"))
            assertFalse(error.message.contains("secret"))
            assertFalse(
                Regex("""[A-Z][A-Za-z]*(Exception|Error)\b|kotlin|ktor|Json""")
                    .containsMatchIn(error.message)
            )
        }
        assertEquals(
            "The server's answer could not be read: the value at \$[0].signedURL is missing or of the wrong type",
            errors[1].message,
        )
        client.closeAndWait()
    }

    @Test
    fun a_transport_failure_is_connectivity_and_never_quotes_the_url() = bridgeTest {
        // Given — a server that cannot be reached
        server.online = false
        val client = openTestClient(server)

        // When — a read filtered on a value nobody should see in an error
        val (_, error) =
            client.selectAndWait(
                """{"v":1,"table":"todos","filters":[{"kind":"filter","column":"token",
                   "operator":"eq","value":"secret-value"}],"strategy":{"kind":"networkOnly"}}"""
            )

        // Then
        assertEquals(BridgeErrorKind.CONNECTIVITY, error?.kind)
        assertEquals(true, error?.isConnectivityFailure)
        assertFalse(error?.message.orEmpty().contains("secret-value"))
        assertFalse(error?.message.orEmpty().contains("fixture.supabase.co"))
        assertEquals("The request could not reach the server", error?.message)
        client.closeAndWait()
    }

    @Test
    fun a_refused_connection_names_neither_the_request_nor_its_values() = bridgeTest {
        // Given — a real client of a local port nothing listens on
        val directory = NSTemporaryDirectory() + "bridge-refused-" + NSUUID().UUIDString()
        try {
            val options =
                BridgeClientOptions(
                    supabaseUrl = "http://127.0.0.1:9",
                    supabaseKey = "fixture-anon-key",
                    dataDirectory = directory,
                )
            val opened = BridgeClient.open(options)
            val client = checkNotNull(opened.client) { "the client did not open: ${opened.error}" }

            // When — a read of a table, filtered on a value nobody should see in an error
            val (_, error) =
                client.selectAndWait(
                    """{"v":1,"table":"patients","filters":[{"kind":"filter","column":"email",
                       "operator":"eq","value":"secret-filter-value"}],"strategy":{"kind":"networkOnly"}}"""
                )

            // Then — the library's sentence, with at most a platform error's domain and code
            assertEquals(BridgeErrorKind.CONNECTIVITY, error?.kind)
            val message = error?.message.orEmpty()
            assertTrue(FIXED_CONNECTIVITY_MESSAGE.matches(message), message)
            for (leak in listOf("secret-filter-value", "patients", "127.0.0.1", "http")) {
                assertFalse(message.contains(leak), "the message names $leak: $message")
            }
            client.closeAndWait()
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
        }
    }

    @Test
    fun a_transport_reason_is_a_platform_errors_domain_and_code_or_a_timeout_and_nothing_else() {
        // Given — the texts supabase-kt keeps of a Darwin failure, a timeout and anything else
        val request =
            HttpRequestBuilder().apply {
                url("https://fixture.supabase.co/rest/v1/patients?email=eq.secret")
            }
        val offline =
            HttpRequestException(
                """Exception in http request: Error Domain=NSURLErrorDomain Code=-1009 "The Internet connection appears to be offline." UserInfo={NSErrorFailingURLStringKey=https://fixture.supabase.co/rest/v1/patients?email=eq.secret}""",
                request,
            )
        val timeout =
            HttpRequestException(
                "Request timeout has expired [url=https://fixture.supabase.co/rest/v1/patients?email=eq.secret]",
                request,
            )

        // Then
        assertEquals("NSURLErrorDomain -1009", transportReason(offline))
        assertEquals("timed out", transportReason(timeout))
        assertNull(transportReason(HttpRequestException("secret went wrong", request)))
        assertNull(transportReason(IOException("Error Domain=NSURLErrorDomain Code=-1009 secret")))
    }

    @Test
    fun a_key_or_token_with_a_control_character_is_refused_without_quoting_its_header() =
        bridgeTest {
            // Given — a client whose token ends in a line break, and one whose key holds one
            val token = "eyJhbGciOi.secret-token-tail\n"
            val tokenClient =
                openTestClient(server) { accessTokenProvider = FakeAccessTokenProvider(token) }
            val keyOpened =
                BridgeTesting.openInMemory(
                    BridgeClientOptions(
                        TEST_URL,
                        "fixture-anon-key\nsecret-key-tail",
                        "/in-memory/bridge-key",
                    ),
                    server,
                    null,
                    null,
                )
            val keyClient = checkNotNull(keyOpened.client) { "the in-memory client did not open" }

            // When
            val (_, tokenError) = tokenClient.selectAndWait(readOf(strategy = "networkOnly"))
            val (_, keyError) = keyClient.selectAndWait(readOf(strategy = "networkOnly"))

            // Then — refused before anything is sent, quoting neither the header nor its value
            for (error in listOf(tokenError, keyError)) {
                assertEquals(BridgeErrorKind.INVALID_ARGUMENT, error?.kind)
                val message = error?.message.orEmpty()
                assertTrue(message.contains("header cannot carry"), message)
                assertFalse(message.contains("Bearer"), message)
                assertFalse(message.contains("secret"), message)
                assertFalse(message.contains("eyJ"), message)
            }
            assertTrue(server.requests.isEmpty())
            tokenClient.closeAndWait()
            keyClient.closeAndWait()
        }

    @Test
    fun a_sync_stopped_by_a_token_with_a_control_character_reports_it_without_quoting_it() =
        bridgeTest {
            // Given — a write queued with the network off, for a client whose token ends in a
            // line break
            val token = "eyJhbGciOi.secret-token-tail\n"
            val client =
                openTestClient(server) {
                    autoSync = false
                    accessTokenProvider = FakeAccessTokenProvider(token)
                }
            awaitErrorOnly { client.disableNetwork(it) }
            client.mutateAndWait(INSERT_TODO, """{"id":2,"title":"Tea"}""")
            awaitErrorOnly { client.enableNetwork(it) }

            // When — a sync stops at the header the HTTP client refuses
            val synced = CompletionProbe<BridgeSyncReport>()
            client.syncPendingMutations(null, synced.completion)
            val report = assertNotNull(synced.awaitValue())
            val error = assertNotNull(client.currentSyncStatus().lastSyncError)

            // Then — the status says so in the sentence a direct call reports, quoting nothing of
            // the header
            assertEquals(1, report.remainingCount)
            assertEquals(BridgeErrorKind.SYNC_FAILED, error.kind)
            val message = error.message
            assertEquals(
                "$ILLEGAL_HEADER_MESSAGE; the queued writes stay queued for a later pass",
                message,
            )
            assertFalse(message.contains("Exception"), message)
            assertFalse(message.contains("Bearer"), message)
            assertFalse(message.contains("secret") || message.contains("eyJ"), message)
            assertTrue(server.requests.isEmpty())
            client.closeAndWait()
        }

    @Test
    fun the_librarys_own_failures_keep_their_kinds() = bridgeTest {
        // Given — a client with nothing cached and its network switched off
        val client = openTestClient(server)
        val (_, cacheMiss) = client.selectAndWait(readOf(strategy = "cacheOnly"))
        awaitErrorOnly { client.disableNetwork(it) }

        // When
        val (_, disabled) = client.selectAndWait(readOf(strategy = "networkOnly"))
        val (_, notQueueable) =
            client.mutateAndWait(
                """{"v":1,"operation":"delete","table":"todos","modifiers":[{"kind":"limit","count":1}],
                   "filters":[{"kind":"filter","column":"id","operator":"eq","value":"1"}]}""",
                null,
            )

        // Then
        assertEquals(BridgeErrorKind.CACHE_MISS, cacheMiss?.kind)
        assertEquals(BridgeErrorKind.NETWORK_DISABLED, disabled?.kind)
        assertEquals(true, disabled?.isConnectivityFailure)
        assertEquals(BridgeErrorKind.MUTATION_NOT_QUEUEABLE, notQueueable?.kind)
        assertTrue(server.requests.isEmpty())
        client.closeAndWait()
    }

    @Test
    fun the_librarys_closed_error_is_client_closed_by_its_type_alone() = bridgeTest {
        // Given — a client whose library plugin was closed under it: the bridge client itself is
        // not closing, so only the failure's type says the client is closed
        val client = openTestClient(server)
        client.supabase.offline.close()

        // When
        val (_, error) = client.selectAndWait(readOf())

        // Then
        assertEquals(BridgeErrorKind.CLIENT_CLOSED, error?.kind)
        assertEquals("OfflineClientClosedException", error?.debugType)
        assertTrue(server.requests.isEmpty())
        client.closeAndWait()
    }

    @Test
    fun a_failure_of_its_own_while_the_client_closes_keeps_its_kind() = bridgeTest {
        // Given — a runner whose client begins closing while each call runs
        val closing = AtomicBoolean(false)
        val runner = OperationRunner(logSink = null) { closing.load() }
        val clearedMessage = "Local data was cleared while this file was downloading"

        // When — one call then fails as a download does when local data is cleared under it, and
        // another with a cancellation that is not its own
        val cleared = CompletionProbe<Unit>()
        runner.runOnce<Unit>("cleared", cleared.completion) {
            closing.store(true)
            throw IllegalStateException(clearedMessage)
        }
        val clearedError = cleared.awaitError()
        closing.store(false)
        val stray = CompletionProbe<Unit>()
        runner.runOnce<Unit>("stray", stray.completion) {
            closing.store(true)
            throw CancellationException("A job the call started was canceled")
        }
        val strayError = stray.awaitError()
        runner.cancelAll()

        // Then — neither is taken for the closed client
        assertEquals(BridgeErrorKind.UNEXPECTED, clearedError.kind)
        assertEquals(clearedMessage, clearedError.message)
        assertEquals("IllegalStateException", clearedError.debugType)
        assertEquals(BridgeErrorKind.UNEXPECTED, strayError.kind)
        assertEquals("CancellationException", strayError.debugType)
    }

    @Test
    fun a_malformed_record_is_refused_and_a_retry_of_no_entry_does_nothing() = bridgeTest {
        // Given — a client and an id with no rejected entry behind it
        val client = openTestClient(server)

        // When — retrying nothing is a no-op; a malformed record is refused
        val retried = awaitErrorOnly { client.retryRejectedMutation(999, it) }
        val malformed = awaitErrorOnly { client.applyServerRecord("todos", "[1]", null, null, it) }

        // Then
        assertEquals(null, retried)
        assertEquals(BridgeErrorKind.INVALID_REQUEST, malformed?.kind)
        client.closeAndWait()
    }

    @Test
    fun an_operator_is_offered_exactly_when_the_pinned_supabase_kt_has_it() = bridgeTest {
        // Given — the operator this pin may or may not have
        val pinHasIsDistinct = FilterOperator.entries.any { it.identifier == "isdistinct" }
        val client = openTestClient(server)

        // When
        val (_, error) =
            client.selectAndWait(
                """{"v":1,"table":"todos","filters":[{"kind":"filter","column":"a",
                   "operator":"isdistinct","value":"1"}],"strategy":{"kind":"networkOnly"}}"""
            )

        // Then — refused with a clear error on a pin without it, sent on one with it
        if (pinHasIsDistinct) {
            assertEquals(null, error)
        } else {
            assertEquals(BridgeErrorKind.UNSUPPORTED, error?.kind)
            assertTrue(error?.message.orEmpty().contains("isdistinct"))
            assertTrue(server.requests.isEmpty())
        }
        client.closeAndWait()
    }
}

/** The connectivity message: the library's sentence, then at most a fixed-vocabulary reason. */
private val FIXED_CONNECTIVITY_MESSAGE =
    Regex(
        """The request could not reach the server( \((timed out|[A-Za-z][A-Za-z0-9_.]* -?\d+)\))?"""
    )
