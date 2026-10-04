// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.Foundation.NSData

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
        assertTrue(error?.message.orEmpty().contains("network down"))
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
