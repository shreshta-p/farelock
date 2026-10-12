package com.farelock.reservation.payment

import com.farelock.reservation.auth.User
import com.farelock.reservation.booking.InventoryRepository
import com.farelock.reservation.booking.ReleaseReason
import com.farelock.reservation.booking.Reservation
import com.farelock.reservation.booking.ReservationRepository
import com.farelock.reservation.booking.ReservationService
import com.farelock.reservation.booking.ReservationStatus
import com.farelock.reservation.common.ApiException
import com.farelock.reservation.common.io
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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

data class PaymentResult(val httpStatus: HttpStatus, val reservation: Reservation)

@Service
class PaymentService(
    private val reservations: ReservationRepository,
    private val reservationService: ReservationService,
    private val inventory: InventoryRepository,
    private val provider: PaymentProvider,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val meters: MeterRegistry,
    @Value("\${farelock.payment.timeout:2s}") private val paymentTimeout: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Pays for a held reservation. Safe to call repeatedly: the reservation's state decides
     * what happens, and the provider's idempotency key (the reservation id) prevents a second charge.
     *
     * 200 = confirmed, 202 = outcome not known yet (reconciliation will finish it).
     */
    suspend fun pay(user: User, reservationId: UUID, token: String): PaymentResult {
        if (token !in PaymentTokens.ALL) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_TOKEN", "Unknown test payment token")
        }
        val reservation = io { reservationService.getFor(user, reservationId) }
        if (reservation.status != ReservationStatus.HELD) return resultFor(reservation)

        // HELD -> PAYMENT_PENDING first. From here on, hold expiry will not release this room.
        if (!io { reservations.startPayment(reservationId, clock.instant()) }) {
            val current = io { reservations.find(reservationId)!! }
            if (current.status == ReservationStatus.HELD) {
                throw ApiException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "Your room hold has expired, please book again")
            }
            return resultFor(current) // a concurrent request started the payment first
        }

        val outcome = callProvider(reservation, token)
        try {
            io { settle(reservation.id, outcome) }
        } catch (e: Exception) {
            // The charge may have succeeded. The reservation stays PAYMENT_PENDING and the reconciler will finish it.
            log.error("Could not record payment outcome {} for reservation {}", outcome, reservationId, e)
        }
        return resultFor(io { reservations.find(reservationId)!! })
    }

    /**
     * Applies a provider outcome. Each branch is a conditional state change plus the matching
     * inventory change in one transaction, so it can safely be called twice (by the request and
     * by the reconciler): the second call finds the status already changed and does nothing.
     */
    fun settle(reservationId: UUID, outcome: ChargeOutcome?): Boolean = tx.execute {
        val r = reservations.find(reservationId)!!
        when (outcome) {
            ChargeOutcome.APPROVED ->
                reservations.transition(r.id, ReservationStatus.PAYMENT_PENDING, ReservationStatus.CONFIRMED)
                    .also { if (it) inventory.convertHoldToBooking(r.roomTypeId, r.stayDate) }
            ChargeOutcome.DECLINED ->
                release(r, ReleaseReason.DECLINED)
            ChargeOutcome.PENDING, null -> false
        }
    }!!

    /** PAYMENT_PENDING -> RELEASED and give the room back. */
    fun release(r: Reservation, reason: ReleaseReason): Boolean =
        reservations.transition(r.id, ReservationStatus.PAYMENT_PENDING, ReservationStatus.RELEASED, reason)
            .also { if (it) inventory.releaseHold(r.roomTypeId, r.stayDate) }

    /**
     * The provider call, bounded by a timeout. `runInterruptible` runs the blocking call on the
     * IO pool and interrupts that thread if `withTimeout` fires. A timeout is not a failure:
     * the money may have moved, so the outcome is "unknown" (null).
     */
    private suspend fun callProvider(r: Reservation, token: String): ChargeOutcome? {
        val outcome = try {
            withTimeout(paymentTimeout.toMillis()) {
                runInterruptible(Dispatchers.IO) { provider.charge(r.id.toString(), r.total, r.currency, token) }
            }
        } catch (e: TimeoutCancellationException) {
            log.warn("Payment provider timed out for reservation {}", r.id)
            null
        } catch (e: Exception) {
            log.warn("Payment provider error for reservation {}: {}", r.id, e.message)
            null
        }
        meters.counter("farelock.payments", "outcome", outcome?.name?.lowercase() ?: "unknown").increment()
        return outcome
    }

    private fun resultFor(r: Reservation): PaymentResult = when (r.status) {
        ReservationStatus.CONFIRMED -> PaymentResult(HttpStatus.OK, r)
        ReservationStatus.PAYMENT_PENDING -> PaymentResult(HttpStatus.ACCEPTED, r)
        ReservationStatus.RELEASED ->
            if (r.releaseReason == ReleaseReason.DECLINED) {
                throw ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "The payment was declined. Your room has been released.")
            } else {
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_RELEASED", "This reservation is no longer active")
            }
        ReservationStatus.CANCELLED ->
            throw ApiException(HttpStatus.CONFLICT, "RESERVATION_CANCELLED", "This reservation was cancelled")
        ReservationStatus.HELD -> throw IllegalStateException("unreachable: payment not started")
    }
}
