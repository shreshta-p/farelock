package com.farelock.reservation

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class ReservationTest : IntegrationTest() {

    @Test
    fun `a reservation holds one room at the quoted price`() {
        seedHotel(rooms = 3)
        val guest = loggedInClient()
        val quote = FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate, amount = "212.50")

        val res = guest.post("/api/reservations", reservationBody(quote), idempotencyKey = "k-1")

        assertEquals(201, res.status)
        assertEquals("HELD", res.body["status"].asString())
        assertEquals(0, java.math.BigDecimal("212.50").compareTo(res.body["amount"].decimalValue()))
        assertEquals(1 to 0, inventory())
    }

    @Test
    fun `retrying with the same key returns the original response without booking twice`() {
        seedHotel(rooms = 3)
        val guest = loggedInClient()
        val body = reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate))

        val first = guest.post("/api/reservations", body, idempotencyKey = "same-key")
        val retry = guest.post("/api/reservations", body, idempotencyKey = "same-key")

        assertEquals(201, retry.status)
        assertEquals(first.body, retry.body)
        assertEquals(1, countRows("SELECT count(*) FROM reservations"))
        assertEquals(1 to 0, inventory())
    }

    @Test
    fun `reusing a key for a different request is rejected with 409`() {
        seedHotel(rooms = 3)
        val guest = loggedInClient()
        val quote = FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate)
        guest.post("/api/reservations", reservationBody(quote, guest = "Ada"), idempotencyKey = "k")

        val res = guest.post("/api/reservations", reservationBody(quote, guest = "Grace"), idempotencyKey = "k")

        assertEquals(409, res.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", res.code)
    }

    @Test
    fun `keys are scoped per user`() {
        seedHotel(rooms = 3)
        val body = reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate))
        assertEquals(201, loggedInClient().post("/api/reservations", body, idempotencyKey = "shared").status)
        assertEquals(201, loggedInClient().post("/api/reservations", body, idempotencyKey = "shared").status)
    }

    @Test
    fun `an expired quote cannot create a reservation, and the answer is stable on retry`() {
        seedHotel(rooms = 3)
        val guest = loggedInClient()
        val body = reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate, validFor = Duration.ofSeconds(-1)))

        val res = guest.post("/api/reservations", body, idempotencyKey = "k")
        val retry = guest.post("/api/reservations", body, idempotencyKey = "k")

        assertEquals("QUOTE_EXPIRED", res.code)
        assertEquals(422, retry.status)
        assertEquals(0 to 0, inventory())
    }

    @Test
    fun `sold out returns 409`() {
        seedHotel(rooms = 1)
        val guest = loggedInClient()
        guest.post("/api/reservations", reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate)), idempotencyKey = "a")

        val res = guest.post("/api/reservations", reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate)), idempotencyKey = "b")

        assertEquals(409, res.status)
        assertEquals("SOLD_OUT", res.code)
    }

    @Test
    fun `pricing outage gives 503 and the same key can be retried once pricing recovers`() {
        seedHotel(rooms = 3)
        val guest = loggedInClient()
        val body = reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate))

        FakePricing.down = true
        val failed = guest.post("/api/reservations", body, idempotencyKey = "k")
        FakePricing.down = false
        val retried = guest.post("/api/reservations", body, idempotencyKey = "k")

        assertEquals("PRICING_UNAVAILABLE", failed.code)
        assertEquals(503, failed.status)
        assertEquals(201, retried.status)
    }

    @Test
    fun `a failed insert rolls back the inventory hold`() {
        seedHotel(rooms = 3)
        val guest = loggedInClient()
        // "EURO" does not fit the CHAR(3) currency column, so the INSERT fails after the room was held.
        val body = reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate, currency = "EURO"))

        val res = guest.post("/api/reservations", body, idempotencyKey = "k")

        assertEquals(500, res.status)
        assertEquals(0 to 0, inventory())
        assertEquals(0, countRows("SELECT count(*) FROM reservations"))
        assertEquals(0, countRows("SELECT count(*) FROM idempotency_keys")) // released, so a retry is possible
    }

    @Test
    fun `users can only see their own reservations`() {
        seedHotel(rooms = 3)
        val owner = loggedInClient()
        val created = owner.post("/api/reservations", reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate)), idempotencyKey = "k")
        val id = created.body["id"].asString()

        val stranger = loggedInClient()
        assertEquals(404, stranger.get("/api/reservations/$id").status)
        assertEquals(0, stranger.get("/api/reservations").body.size())
        assertEquals(200, owner.get("/api/reservations/$id").status)
        assertEquals(1, owner.get("/api/reservations").body.size())
    }

    @Test
    fun `the request id is echoed back and forwarded to the pricing service`() {
        seedHotel(rooms = 3)
        val guest = loggedInClient()
        val body = reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate))

        val res = guest.post("/api/reservations", body, idempotencyKey = "k", requestId = "trace-abc-123")

        assertEquals("trace-abc-123", res.headers.getFirst("X-Request-Id"))
        // The quote call ran on a coroutine IO thread; the id must still have reached it.
        assertEquals("trace-abc-123", FakePricing.lastRequestId)
    }
}
