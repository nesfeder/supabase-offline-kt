// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalForeignApi::class)

package io.github.nesfeder.supabase.offline.bridge

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.bridge.internal.transportReason
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
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
