// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.storage.StorageRestException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

/*
 * supabase-kt's Storage failures, built the way the service surfaces them: a StorageRestException
 * for every error answer, whose status is the one Storage's error body names. It needs a live HTTP
 * response, so each is built over one from a MockEngine.
 */

/**
 * A missing object as hosted Supabase Storage may report it: an HTTP 400 whose body says 404.
 * `statusCode` reads the body's 404, so the status alone marks the object missing.
 */
internal suspend fun notFoundStorageException(): StorageRestException =
    StorageRestException(
        error = "not_found",
        description = "Object not found",
        response = httpResponse(HttpStatusCode.BadRequest),
        statusCode = HttpStatusCode.NotFound.value,
        code = "NoSuchKey",
    )

/** A 403, as an expired token or an RLS rule produces; it says nothing about the object. */
internal suspend fun forbiddenStorageException(): StorageRestException =
    StorageRestException(
        error = "Unauthorized",
        description = "new row violates row-level security policy",
        response = httpResponse(HttpStatusCode.Forbidden),
        statusCode = HttpStatusCode.Forbidden.value,
        code = "AccessDenied",
    )

/**
 * Storage's error answer carried on an HTTP [httpStatus] whose body names [bodyStatus], [error] and
 * [code], as supabase-kt raises it: its `statusCode` is [bodyStatus].
 */
internal suspend fun storageRestException(
    httpStatus: HttpStatusCode,
    bodyStatus: Int,
    error: String,
    code: String = error,
): StorageRestException =
    StorageRestException(
        error = error,
        description = "refused by the test",
        response = httpResponse(httpStatus),
        statusCode = bodyStatus,
        code = code,
    )

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
