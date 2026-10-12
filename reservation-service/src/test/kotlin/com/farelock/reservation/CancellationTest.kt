package com.farelock.reservation

import com.farelock.reservation.payment.BackgroundJobs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class CancellationTest : IntegrationTest() {
    @Autowired lateinit var jobs: BackgroundJobs

    /** Books and pays for a room (quote: 150.00 EUR room + 15.00 EUR protection fee). */
    private fun confirmedStay(protection: Boolean): Pair<TestClient, String> {
        val (guest, id) = heldStay(protection)
        val paid = guest.post("/api/reservations/$id/payment", """{"paymentToken":"tok_approve"}""")
        check(paid.status == 200) { paid.body.toString() }
        return guest to id
    }

    private fun heldStay(protection: Boolean): Pair<TestClient, String> {
        seedHotel(rooms = 5)
        val guest = loggedInClient()
        val quote = FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate)
        val res = guest.post("/api/reservations", reservationBody(quote, protection = protection), idempotencyKey = "k")
        check(res.status == 201) { res.body.toString() }
        return guest to res.body["id"].asString()
    }

    private fun cancel(guest: TestClient, id: String) = guest.post("/api/reservations/$id/cancel")

    private fun refunds() = countRows("SELECT count(*) FROM payment_provider.refunds")

    private fun money(sql: String): BigDecimal = jdbc.sql(sql).query(BigDecimal::class.java).single()

    @Test
    fun `protected stay cancelled in time gets the room price back, not the fee`() {
        val (guest, id) = confirmedStay(protection = true)
        assertEquals(0, BigDecimal("165.00").compareTo(money("SELECT amount FROM payment_provider.charges")))

        val res = cancel(guest, id)

        assertEquals(200, res.status)
        assertEquals("CANCELLED", res.body["status"].asString())
        assertEquals("REFUNDED", res.body["refundStatus"].asString())
        assertEquals(0, BigDecimal("150.00").compareTo(money("SELECT amount FROM payment_provider.refunds")))
        assertEquals(0 to 0, inventory()) // the room is back on sale
    }

    @Test
    fun `unprotected stay can be cancelled but is not refunded`() {
        val (guest, id) = confirmedStay(protection = false)

        val res = cancel(guest, id)

        assertEquals("CANCELLED", res.body["status"].asString())
        assertEquals("NONE", res.body["refundStatus"].asString())
        assertEquals(0, refunds())
        assertEquals(1, countRows("SELECT count(*) FROM payment_provider.charges")) // the charge stands
        assertEquals(0 to 0, inventory())
    }

    @Test
    fun `cancelling an unpaid hold releases it and it can no longer be paid`() {
        val (guest, id) = heldStay(protection = true)

        assertEquals("NONE", cancel(guest, id).body["refundStatus"].asString()) // nothing was charged
        assertEquals(0 to 0, inventory())
        assertEquals("RESERVATION_CANCELLED", guest.post("/api/reservations/$id/payment", """{"paymentToken":"tok_approve"}""").code)
        assertEquals(0, countRows("SELECT count(*) FROM payment_provider.charges"))
    }

    @Test
    fun `many simultaneous cancels refund once and free the room once`() {
        val (guest, id) = confirmedStay(protection = true)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val statuses = (1..8).map { pool.submit<Int> { start.await(); cancel(guest, id).status } }

        start.countDown()
        val results = statuses.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(8) { 200 }, results)
        assertEquals(1, refunds())
        assertEquals(0 to 0, inventory()) // booked went 1 -> 0 exactly once
    }

    @Test
    fun `cancellation closes when check-in day starts at the hotel`() {
        val (guest, id) = confirmedStay(protection = true)
        jdbc.sql("UPDATE reservations SET cancel_deadline = now() - interval '1 second' WHERE id = ?::uuid").param(id).update()

        val res = cancel(guest, id)

        assertEquals(409, res.status)
        assertEquals("CANCELLATION_CLOSED", res.code)
        assertEquals("CONFIRMED", guest.get("/api/reservations/$id").body["status"].asString())
        assertEquals(0, refunds())
    }

    @Test
    fun `the deadline is midnight in the hotel's own time zone`() {
        seedHotel(rooms = 5)
        jdbc.sql("UPDATE hotels SET timezone = 'Asia/Tokyo' WHERE id = ?").param(TestHotel.ID).update()
        val guest = loggedInClient()
        val res = guest.post("/api/reservations", reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate)), idempotencyKey = "k")

        val expected = stayDate.atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant() // 15:00 UTC the day before
        assertEquals(expected, Instant.parse(res.body["cancelDeadline"].asString()))
    }

    @Test
    fun `a refund that was recorded but never reached the provider is retried once`() {
        val (_, id) = confirmedStay(protection = true)
        // State left by a crash between "cancel recorded" and "provider refunded":
        jdbc.sql(
            """
            UPDATE reservations SET status = 'CANCELLED', cancelled_at = now(), refund_amount = 150,
                   refund_status = 'PENDING', updated_at = now() - interval '1 minute'
            WHERE id = ?::uuid
            """
        ).param(id).update()

        assertEquals(mapOf("refunded" to 1), jobs.retryRefunds())
        assertEquals(emptyMap(), jobs.retryRefunds()) // nothing left to do
        assertEquals(1, refunds())
        assertEquals("REFUNDED", jdbc.sql("SELECT refund_status FROM reservations WHERE id = ?::uuid").param(id).query(String::class.java).single())
    }

    @Test
    fun `cancel is refused while a payment is unresolved, and other users get 404`() {
        val (guest, id) = heldStay(protection = false)
        assertEquals(404, cancel(loggedInClient(), id).status)

        jdbc.sql("UPDATE reservations SET status = 'PAYMENT_PENDING' WHERE id = ?::uuid").param(id).update()
        assertEquals("PAYMENT_IN_PROGRESS", cancel(guest, id).code)
    }
}
