package com.farelock.reservation

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class TestResponse(val status: Int, val body: JsonNode, val headers: HttpHeaders) {
    val code: String get() = body["code"]?.asString() ?: ""
}

/** A tiny HTTP client that remembers its session cookie, like one browser tab. */
class TestClient(port: Int, private val json: JsonMapper) {
    private val http = RestClient.create("http://localhost:$port")
    var sessionCookie: String? = null

    fun get(path: String) = send(http.get().uri(path))

    fun post(path: String, body: String = "{}", idempotencyKey: String? = null) = send(
        http.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .body(body)
    )

    private fun send(spec: RestClient.RequestHeadersSpec<*>): TestResponse {
        sessionCookie?.let { spec.header(HttpHeaders.COOKIE, it) }
        return spec.exchange { _, res ->
            res.headers[HttpHeaders.SET_COOKIE]?.firstOrNull()?.let { sessionCookie = it.substringBefore(';') }
            val text = res.body.readAllBytes().decodeToString()
            TestResponse(res.statusCode.value(), json.readTree(text.ifBlank { "{}" }), res.headers)
        }!!
    }
}
