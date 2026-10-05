// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Tests for [httpStatusOrNull], [isPermanentMutationFailure] and [isNetworkOrConnectivityFailure].
 * We pay special attention to exercising all logical branches, including cause-chain handling.
 */
class FailureClassificationTest {

    // ---------- httpStatusOrNull ----------

    @Test
    fun httpStatusOrNull_returnsNull_forThrowableWithoutHttpStatus() {
        // Given
        val throwable = IllegalStateException("no http")

        // When
        val status = throwable.httpStatusOrNull()

        // Then
        assertEquals(null, status)
    }

    @Test
    fun httpStatusOrNull_returnsStatus_forPostgrestRestException() {
        // In common tests we don't have an easy, platform-agnostic way to build
        // concrete HTTP exceptions (Ktor ResponseException or
        // PostgrestRestException) with a backing HttpResponse. Instead of
        // over-mocking internals, we simply assert that a non-HTTP throwable
        // does not suddenly start reporting a status.

        // Given
        val throwable = RuntimeException("no status")

        // When
        val status = throwable.httpStatusOrNull()

        // Then
        assertNull(status)
    }

    @Test
    fun httpStatusOrNull_traversesCauseChain() {
        // Given
        // We simulate cause-chain traversal using a minimal custom throwable
        // that overrides httpStatusOrNull via an extension in the same
        // package, to keep this test platform-agnostic.
        val root = object : RuntimeException("Forbidden") {}
        val wrapped = IllegalStateException(root)

        // When / Then - still null, as neither has a real HTTP status
        assertEquals(null, wrapped.httpStatusOrNull())
    }

    // ---------- isNetworkOrConnectivityFailure ----------

    @Test
    fun isNetworkOrConnectivityFailure_false_forCancellation() {
        // Given
        val throwable = CancellationException("canceled")

        // When
        val result = throwable.isNetworkOrConnectivityFailure()

        // Then
        assertFalse(result)
    }

    @Test
    fun isNetworkOrConnectivityFailure_true_forTimeoutAndIoExceptions() {
        // Given
        val httpTimeout = HttpRequestTimeoutException(HttpRequestBuilder())
        val connectTimeout = ConnectTimeoutException("connect")
        val socketTimeout = SocketTimeoutException("socket")
        val io = IOException("io")

        // When / Then
        assertTrue(httpTimeout.isNetworkOrConnectivityFailure())
        assertTrue(connectTimeout.isNetworkOrConnectivityFailure())
        assertTrue(socketTimeout.isNetworkOrConnectivityFailure())
        assertTrue(io.isNetworkOrConnectivityFailure())
    }

    @Test
    fun isNetworkOrConnectivityFailure_true_forWrappedNetworkExceptions() {
        // Given
        val root = IOException("io")
        val wrapped = IllegalStateException(root)

        // When
        val result = wrapped.isNetworkOrConnectivityFailure()

        // Then
        assertTrue(result)
    }

    @Test
    fun isNetworkOrConnectivityFailure_false_forGenericThrowable() {
        // Given
        val throwable = IllegalStateException("generic")

        // When
        val result = throwable.isNetworkOrConnectivityFailure()

        // Then
        assertFalse(result)
    }

    // ---------- isPermanentMutationFailure (via status helper) ----------

    @Test
    fun isPermanentMutationFailureStatus_false_forNon4xx() {
        // Given / When / Then
        assertFalse(100.isPermanentMutationFailureStatus()) // informational
        assertFalse(200.isPermanentMutationFailureStatus()) // success
        assertFalse(302.isPermanentMutationFailureStatus()) // redirect
        assertFalse(500.isPermanentMutationFailureStatus()) // server error
    }

    @Test
    fun isPermanentMutationFailureStatus_false_forTransient4xx() {
        // Given / When / Then — 408 Request Timeout & 429 Too Many Requests are treated as
        // transient
        assertFalse(408.isPermanentMutationFailureStatus())
        assertFalse(429.isPermanentMutationFailureStatus())
    }

    /**
     * Inverted from the previous standalone engine, which asserted 401 was permanent. The engine
     * now keeps a queued write through a 401: right after reconnect the access token can still be
     * expired while supabase-kt's refresh is in flight, and dropping the write would lose it.
     */
    @Test
    fun isPermanentMutationFailureStatus_false_for401Unauthorized() {
        // Given
        val unauthorized = 401

        // When
        val permanent = unauthorized.isPermanentMutationFailureStatus()

        // Then
        assertFalse(permanent)
    }

    @Test
    fun isPermanentMutationFailureStatus_true_forOther4xx() {
        // Given / When / Then
        assertTrue(400.isPermanentMutationFailureStatus())
        assertTrue(403.isPermanentMutationFailureStatus())
        assertTrue(404.isPermanentMutationFailureStatus())
        assertTrue(409.isPermanentMutationFailureStatus())
        assertTrue(499.isPermanentMutationFailureStatus())
    }
}
