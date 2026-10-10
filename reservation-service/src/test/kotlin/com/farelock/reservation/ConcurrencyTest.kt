package com.farelock.reservation

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real concurrent HTTP requests against the running app and a real Postgres.
 * A CountDownLatch releases every thread at the same moment, so the requests genuinely
 * race each other. No sleeps, so the outcome does not depend on timing.
 */
class ConcurrencyTest : IntegrationTest() {

    /** Runs [task] on [threads] threads that all start at the same instant. */
    private fun <T> race(threads: Int, task: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val startGate = CountDownLatch(1)
            val futures = (0 until threads).map { i -> pool.submit<T> { startGate.await(); task(i) } }
            startGate.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `100 customers competing for 10 rooms - exactly 10 succeed`() {
        seedHotel(rooms = 10)
        val customers = Executors.newFixedThreadPool(16).let { pool ->
            (1..100).map { pool.submit<TestClient> { loggedInClient() } }.map { it.get() }.also { pool.shutdown() }
        }

        val bodies = customers.map { reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate)) }

        val statuses = race(100) { i -> customers[i].post("/api/reservations", bodies[i], idempotencyKey = "k-$i") }
            .map { it.status to it.code }

        assertEquals(10, statuses.count { it.first == 201 })
        assertEquals(90, statuses.count { it == (409 to "SOLD_OUT") })
        assertEquals(10 to 0, inventory())
        assertEquals(10, countRows("SELECT count(*) FROM reservations"))
    }

    @Test
    fun `20 simultaneous retries with one idempotency key create exactly one reservation`() {
        seedHotel(rooms = 10)
        val guest = loggedInClient()
        val body = reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate))

        val responses = race(20) { guest.post("/api/reservations", body, idempotencyKey = "double-click") }

        // Each response is either the booking (fresh or replayed) or "still in progress, retry".
        val created = responses.filter { it.status == 201 }
        assertTrue(created.isNotEmpty())
        assertTrue(responses.all { it.status == 201 || it.code == "REQUEST_IN_PROGRESS" })
        assertEquals(1, created.map { it.body["id"].asString() }.distinct().size)
        assertEquals(1, countRows("SELECT count(*) FROM reservations"))
        assertEquals(1 to 0, inventory())
    }
}
