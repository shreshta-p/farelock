package com.farelock.reservation.payment

import com.farelock.reservation.auth.User
import com.farelock.reservation.booking.InventoryRepository
import com.farelock.reservation.booking.RefundStatus
import com.farelock.reservation.booking.Reservation
import com.farelock.reservation.booking.ReservationRepository
import com.farelock.reservation.booking.ReservationService
import com.farelock.reservation.booking.ReservationStatus
import com.farelock.reservation.common.ApiException
import com.farelock.reservation.common.io
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Guest cancellation. Allowed until check-in day starts at the hotel (the reservation's
 * cancelDeadline). With cancellation protection the room price is refunded and the fee is kept;
 * without it the room goes back on sale but the charge stands.
 *
 * Same shape as payment: first record the decision in our database (one transaction), then talk
 * to the provider. If the provider call fails, the refund stays PENDING and the reconciler retries
 * it with the same idempotency key, so the guest is refunded exactly once.
 */
@Service
class CancellationService(
    private val reservations: ReservationRepository,
    private val reservationService: ReservationService,
    private val inventory: InventoryRepository,
    private val provider: PaymentProvider,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val meters: MeterRegistry,
    @Value("\${farelock.payment.timeout:2s}") private val providerTimeout: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Safe to call repeatedly: a second call finds the reservation already CANCELLED and returns it. */
    suspend fun cancel(user: User, id: UUID): Reservation {
        val r = io { reservationService.getFor(user, id) }
        when (r.status) {
            ReservationStatus.CANCELLED -> return r
            ReservationStatus.RELEASED ->
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_RELEASED", "This reservation is no longer active")
            ReservationStatus.PAYMENT_PENDING ->
                throw ApiException(HttpStatus.CONFLICT, "PAYMENT_IN_PROGRESS", "We're still confirming the payment, please try again shortly")
            ReservationStatus.HELD, ReservationStatus.CONFIRMED -> {}
        }
        if (!clock.instant().isBefore(r.cancelDeadline)) throw cancellationClosed()

        if (!io { recordCancellation(r) }) {
            // Someone changed it first: a parallel cancel (fine), or payment/expiry/deadline (not fine).
            val current = io { reservations.find(id)!! }
            if (current.status == ReservationStatus.CANCELLED) return current
            if (!clock.instant().isBefore(r.cancelDeadline)) throw cancellationClosed()
            throw ApiException(HttpStatus.CONFLICT, "RESERVATION_CHANGED", "This reservation just changed, please reload and try again")
        }

        val cancelled = io { reservations.find(id)!! }
        if (cancelled.refundStatus == RefundStatus.PENDING) {
            try {
                withTimeout(providerTimeout.toMillis()) { runInterruptible(Dispatchers.IO) { refund(cancelled) } }
            } catch (e: Exception) {
                // Not lost: the refund is recorded as PENDING and the reconciler will retry it.
                log.warn("Refund for reservation {} not completed yet: {}", id, e.message)
                meters.counter("farelock.refunds", "outcome", "deferred").increment()
            }
        }
        return io { reservations.find(id)!! }
    }

    /**
     * One transaction: the status change, the refund owed, and the room back on sale. Only a
     * CONFIRMED stay with protection is refunded; a HELD reservation was never charged.
     */
    private fun recordCancellation(r: Reservation): Boolean = tx.execute {
        val refund = if (r.status == ReservationStatus.CONFIRMED && r.protectionFee != null) r.amount else null
        val changed = reservations.cancel(r.id, from = r.status, refundAmount = refund, now = clock.instant())
        if (changed) {
            when (r.status) {
                ReservationStatus.HELD -> inventory.releaseHold(r.roomTypeId, r.stayDate)
                else -> inventory.releaseBooking(r.roomTypeId, r.stayDate)
            }
            meters.counter("farelock.cancellations", "refund", if (refund == null) "none" else "full").increment()
        }
        changed
    }!!

    /**
     * Asks the provider for the refund, then marks it done. Blocking; used by the request (under a
     * timeout) and by the reconciler. The refund key is derived from the reservation id, so any
     * number of retries produce one refund.
     */
    fun refund(r: Reservation) {
        provider.refund(refundKey(r.id), chargeKey = r.id.toString(), amount = r.refundAmount!!, currency = r.currency)
        if (reservations.markRefunded(r.id)) meters.counter("farelock.refunds", "outcome", "refunded").increment()
    }

    private fun refundKey(id: UUID) = "refund:$id"

    private fun cancellationClosed() = ApiException(HttpStatus.CONFLICT, "CANCELLATION_CLOSED",
        "Check-in day has started at the hotel, so this stay can no longer be cancelled")
}
