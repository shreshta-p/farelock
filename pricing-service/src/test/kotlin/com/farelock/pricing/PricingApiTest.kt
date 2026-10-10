package com.farelock.pricing

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class PricingApiTest {
    @LocalServerPort var port = 0
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var json: JsonMapper

    private val http by lazy { RestClient.create("http://localhost:$port") }
    private val stayDate = LocalDate.now(ZoneOffset.UTC).plusDays(30)

    @BeforeEach
    fun seed() {
        jdbc.sql("DELETE FROM quotes").update()
        jdbc.sql("DELETE FROM room_rates").update()
        jdbc.sql("INSERT INTO room_rates VALUES (11, 1, 180.00, 'EUR'), (12, 1, 320.00, 'EUR')").update()
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
    }

    @Test
    fun `prices are computed from base rates and cached on the second request`() {
        val hitsBefore = cacheCount("hit")

        val first = get("/api/prices?hotelId=1&date=$stayDate")
        val second = get("/api/prices?hotelId=1&date=$stayDate")

        assertEquals(200, first.status)
        assertEquals(first.body, second.body)
        val expected = PricingRules.nightlyPrice(BigDecimal("180.00"), "EUR", stayDate, LocalDate.now(ZoneOffset.UTC))
        assertEquals(0, expected.compareTo(first.body["prices"][0]["amount"].decimalValue()))
        assertEquals("EUR", first.body["prices"][0]["currency"].asString())
        assertEquals(hitsBefore + 1, cacheCount("hit"))
    }

    @Test
    fun `a quote freezes the price and reports when it has expired`() {
        val created = post("/api/quotes", """{"roomTypeId": 12, "stayDate": "$stayDate"}""")
        assertEquals(201, created.status)
        val id = created.body["id"].asString()
        assertFalse(created.body["expired"].asBoolean())

        // Base rates can change later; the quote must keep its original amount.
        jdbc.sql("UPDATE room_rates SET base_price = 999.00 WHERE room_type_id = 12").update()
        jdbc.sql("UPDATE quotes SET expires_at = now() - interval '1 second' WHERE id = ?::uuid").param(id).update()

        val fetched = get("/api/quotes/$id")
        assertEquals(0, created.body["amount"].decimalValue().compareTo(fetched.body["amount"].decimalValue()))
        assertTrue(fetched.body["expired"].asBoolean())
    }

    @Test
    fun `invalid input returns clear errors`() {
        assertEquals("BAD_REQUEST", get("/api/prices?hotelId=1&date=2001-01-01").body["code"].asString())
        assertEquals(404, post("/api/quotes", """{"roomTypeId": 999, "stayDate": "$stayDate"}""").status)
        assertEquals("VALIDATION_FAILED", post("/api/quotes", """{"stayDate": "$stayDate"}""").body["code"].asString())
        assertEquals(404, get("/api/quotes/00000000-0000-0000-0000-000000000000").status)
    }

    private fun cacheCount(result: String) =
        meters.find("farelock.cache").tag("result", result).counter()?.count() ?: 0.0

    data class Response(val status: Int, val body: JsonNode)

    private fun get(path: String): Response = http.get().uri(path)
        .exchange { _, res -> Response(res.statusCode.value(), json.readTree(res.body)) }!!

    private fun post(path: String, body: String): Response = http.post().uri(path)
        .contentType(MediaType.APPLICATION_JSON).body(body)
        .exchange { _, res -> Response(res.statusCode.value(), json.readTree(res.body)) }!!
}
