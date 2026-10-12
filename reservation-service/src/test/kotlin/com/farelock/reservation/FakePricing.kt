package com.farelock.reservation

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A stand-in for the Pricing Service: a real HTTP server on a random port that serves
 * quotes the test registered. Setting [down] makes it answer 503, like an outage.
 */
object FakePricing {
    private val quotes = ConcurrentHashMap<UUID, String>()
    @Volatile var down = false
    /** The X-Request-Id header of the last quote request, to check correlation ids are forwarded. */
    @Volatile var lastRequestId: String? = null

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/api/quotes/") { exchange ->
            lastRequestId = exchange.requestHeaders.getFirst("X-Request-Id")
            val id = UUID.fromString(exchange.requestURI.path.substringAfterLast('/'))
            val body = quotes[id]
            val status = when {
                down -> 503
                body == null -> 404
                else -> 200
            }
            val bytes = (if (status == 200) body!! else """{"code":"X","message":"x"}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        createContext("/api/prices") { exchange ->
            val date = exchange.requestURI.query.substringAfter("date=").substringBefore('&')
            val body = """{"hotelId":${TestHotel.ID},"stayDate":"$date","prices":[{"roomTypeId":${TestHotel.ROOM_TYPE_ID},"amount":180.00,"currency":"EUR"}]}"""
            val status = if (down) 503 else 200
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        executor = java.util.concurrent.Executors.newFixedThreadPool(16)
        start()
    }

    val url: String get() = "http://localhost:${server.address.port}"

    fun quote(
        roomTypeId: Long,
        stayDate: LocalDate,
        amount: String = "150.00",
        currency: String = "EUR",
        validFor: Duration = Duration.ofMinutes(15),
    ): UUID {
        val id = UUID.randomUUID()
        val expiresAt = Instant.now().plus(validFor)
        quotes[id] = """
            {"id":"$id","roomTypeId":$roomTypeId,"hotelId":${TestHotel.ID},"stayDate":"$stayDate",
             "amount":$amount,"protectionFee":15.00,"currency":"$currency","expiresAt":"$expiresAt","expired":${validFor.isNegative}}
        """.trimIndent()
        return id
    }
}
