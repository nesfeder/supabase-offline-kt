package io.github.nesfeder.supabase.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

suspend fun makeFakeHttpResponse(
    status: HttpStatusCode = HttpStatusCode.BadRequest,
    body: String = """{"error":"bad"}""",
    headers: Headers = headersOf("Content-Type" to listOf("application/json"))
): HttpResponse {
    val mockEngine = MockEngine { request ->
        respond(
            content = body,
            status = status,
            headers = headers
        )
    }

    val client = HttpClient(mockEngine)

    return client.get("https://fake.test/endpoint")
}