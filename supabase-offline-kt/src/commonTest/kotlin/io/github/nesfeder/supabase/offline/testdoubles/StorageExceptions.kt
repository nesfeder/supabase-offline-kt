// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

/*
 * supabase-kt's Storage failures, built the way the service surfaces them. Its RestException
 * subclasses need a live HTTP response, so each is built over one from a MockEngine.
 */

/**
 * A missing object as Supabase Storage reports it: HTTP 400 with 404 in the body. `statusCode`
 * reads 400, so only the exception type marks the object missing.
 */
internal suspend fun notFoundRestException(): NotFoundRestException =
    NotFoundRestException(
        "Object not found",
        httpResponse(HttpStatusCode.BadRequest),
        "Object not found",
    )

/** A 403, as an expired token or an RLS rule produces; it says nothing about the object. */
internal suspend fun forbiddenRestException(): UnauthorizedRestException =
    UnauthorizedRestException("Forbidden", httpResponse(HttpStatusCode.Forbidden), "Forbidden")

/** A real Ktor [HttpResponse] with [status]. */
internal suspend fun httpResponse(status: HttpStatusCode): HttpResponse {
    val client =
        HttpClient(MockEngine) {
            engine { addHandler { respondError(status) } }
            expectSuccess = true
        }
    return try {
        client.get("$TEST_SUPABASE_URL/storage/v1/object/authenticated/bucket/object")
        error("the mock engine answered $status with a success")
    } catch (failure: ResponseException) {
        failure.response
    }
}
