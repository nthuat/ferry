package dev.thuat.ferry

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** FIFO fake server: enqueue responses, fire requests, inspect what was asked. */
class QueueClient {
    private data class Canned(val body: ByteArray, val status: HttpStatusCode, val headers: Headers)

    private val queue = ArrayDeque<Canned>()
    val requests = mutableListOf<HttpRequestData>()

    val client: HttpClient = HttpClient(MockEngine { request ->
        requests += request
        val next = queue.removeFirstOrNull()
            ?: error("no response enqueued for ${request.url}")
        respond(next.body, next.status, next.headers)
    })

    fun enqueue(
        body: String = "",
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: Headers = headersOf(),
        bodyBytes: ByteArray? = null,
    ) {
        queue += Canned(bodyBytes ?: body.encodeToByteArray(), status, headers)
    }
}
