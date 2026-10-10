package com.farelock.reservation

import com.farelock.reservation.payment.BackgroundJobs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class PaymentTest : IntegrationTest() {
    @Autowired lateinit var jobs: BackgroundJobs

    /** Logs in, holds a room and returns the client and reservation id. */
    private fun heldReservation(): Pair<TestClient, String> {
        seedHotel(rooms = 5)
        val guest = loggedInClient()
        val res = guest.post("/api/reservations", reservationBody(FakePricing.quote(TestHotel.ROOM_TYPE_ID, stayDate)), idempotencyKey = "k")
        check(res.status == 201) { res.body.toString() }
        return guest to res.body["id"].asString()
    }

    private fun pay(guest: TestClient, id: String, token: String) =
        guest.post("/api/reservations/$id/payment", """{"paymentToken":"$token"}""")

    private fun status(id: String) =
        jdbc.sql("SELECT status || coalesce(':' || release_reason, '') FROM reservations WHERE id = ?::uuid")
            .param(id).query(String::class.java).single()

    private fun charges() = countRows("SELECT count(*) FROM payment_provider.charges")

    /** Makes the reservation look like it has been stuck for [minutes] minutes. */
    private fun age(id: String, minutes: Int) =
        jdbc.sql("UPDATE reservations SET updated_at = now() - make_interval(mins => ?) WHERE id = ?::uuid")
            .params(minutes, id).update()

    @Test
    fun `approved payment confirms the reservation and books the room`() {
        val (guest, id) = heldReservation()

        val res = pay(guest, id, "tok_approve")

        assertEquals(200, res.status)
        assertEquals("CONFIRMED", res.body["status"].asString())
        assertEquals(0 to 1, inventory())
        assertEquals(1, charges())
    }

    @Test
    fun `paying twice never charges twice`() {
        val (guest, id) = heldReservation()
        pay(guest, id, "tok_approve")

        val again = pay(guest, id, "tok_approve")

        assertEquals(200, again.status)
        assertEquals(1, charges())
        assertEquals(0 to 1, inventory())
    }

    @Test
    fun `declined payment releases the room`() {
        val (guest, id) = heldReservation()

        val res = pay(guest, id, "tok_decline")

        assertEquals(402, res.status)
        assertEquals("PAYMENT_DECLINED", res.code)
        assertEquals("RELEASED:DECLINED", status(id))
        assertEquals(0 to 0, inventory())
    }

    @Test
    fun `provider timeout leaves the payment pending, and reconciliation releases it once the provider has no record`() {
        val (guest, id) = heldReservation()

        val res = pay(guest, id, "tok_timeout")
        assertEquals(202, res.status)
        assertEquals("PAYMENT_PENDING", status(id))
        assertEquals(1 to 0, inventory()) // room still held: we don't know the outcome yet

        age(id, minutes = 1)
        jobs.reconcilePayments()
        assertEquals("PAYMENT_PENDING", status(id)) // within the grace period: keep waiting

        age(id, minutes = 3)
        jobs.reconcilePayments()
        assertEquals("RELEASED:PAYMENT_NOT_FOUND", status(id))
        assertEquals(0 to 0, inventory())
        assertEquals(0, charges())
    }

    @Test
    fun `lost response - the provider charged but we never heard back - is confirmed by reconciliation`() {
        val (guest, id) = heldReservation()

        val res = pay(guest, id, "tok_lost_response")
        assertEquals(202, res.status)
        assertEquals(1, charges()) // the provider did take the money

        age(id, minutes = 1)
        jobs.reconcilePayments()

        assertEquals("CONFIRMED", status(id))
        assertEquals(0 to 1, inventory())
        assertEquals(1, charges())
    }

    @Test
    fun `unknown outcome is never released while the provider says pending`() {
        val (guest, id) = heldReservation()
        assertEquals(202, pay(guest, id, "tok_unknown").status)

        age(id, minutes = 10)
        jobs.reconcilePayments()
        assertEquals("PAYMENT_PENDING", status(id))

        // The provider settles the charge later.
        jdbc.sql("UPDATE payment_provider.charges SET created_at = now() - interval '1 minute'").update()
        jobs.reconcilePayments()
        assertEquals("CONFIRMED", status(id))
    }

    @Test
    fun `crash after the provider approved but before we confirmed is recovered`() {
        val (_, id) = heldReservation()
        // State left behind by a crash: payment started, provider recorded APPROVED, confirmation never ran.
        jdbc.sql("UPDATE reservations SET status = 'PAYMENT_PENDING', updated_at = now() - interval '1 minute' WHERE id = ?::uuid").param(id).update()
        jdbc.sql("INSERT INTO payment_provider.charges (idempotency_key, amount, currency, outcome) VALUES (?, 150, 'EUR', 'APPROVED')").param(id).update()

        val summary = jobs.reconcilePayments()

        assertEquals(mapOf("confirmed" to 1), summary)
        assertEquals("CONFIRMED", status(id))
        assertEquals(0 to 1, inventory())
    }

    @Test
    fun `expired holds are released and can no longer be paid`() {
        val (guest, id) = heldReservation()
        jdbc.sql("UPDATE reservations SET hold_expires_at = now() - interval '1 second'").update()

        assertEquals("HOLD_EXPIRED", pay(guest, id, "tok_approve").code) // before the job runs

        assertEquals(1, jobs.expireHolds())
        assertEquals("RELEASED:EXPIRED", status(id))
        assertEquals(0 to 0, inventory())
        assertEquals("RESERVATION_RELEASED", pay(guest, id, "tok_approve").code)
        assertEquals(0, charges())
    }

    @Test
    fun `payment input and ownership are checked`() {
        val (guest, id) = heldReservation()
        assertEquals("INVALID_PAYMENT_TOKEN", pay(guest, id, "4111111111111111").code)
        assertEquals(404, pay(loggedInClient(), id, "tok_approve").status)
        assertEquals("HELD", status(id))
    }
}
