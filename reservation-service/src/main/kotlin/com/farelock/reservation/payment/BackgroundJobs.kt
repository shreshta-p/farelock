package com.farelock.reservation.payment

import com.farelock.reservation.booking.InventoryRepository
import com.farelock.reservation.booking.ReleaseReason
import com.farelock.reservation.booking.ReservationRepository
import com.farelock.reservation.booking.ReservationStatus
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Two small recovery jobs. Neither holds locks or long transactions: every change is a
 * conditional state transition, so running a job twice (or on two instances) is harmless.
 */
@Component
class BackgroundJobs(
    private val reservations: ReservationRepository,
    private val inventory: InventoryRepository,
    private val provider: PaymentProvider,
    private val payments: PaymentService,
    private val cancellations: CancellationService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val meters: MeterRegistry,
    @Value("\${farelock.jobs.reconcile-after:15s}") private val reconcileAfter: Duration,
    @Value("\${farelock.jobs.payment-not-found-after:2m}") private val notFoundAfter: Duration,
    @Value("\${farelock.booking-window-days:90}") private val bookingWindowDays: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Keeps the bookable window (today + 90 days) stocked as the calendar moves forward. */
    fun extendInventory(): Int =
        inventory.extendWindow(LocalDate.now(clock), bookingWindowDays)
            .also { if (it > 0) log.info("Added {} inventory rows", it) }

    /** Gives back rooms whose hold expired before the guest started paying. */
    fun expireHolds(): Int {
        val expired = reservations.findOlderThan(ReservationStatus.HELD, "hold_expires_at", clock.instant(), 100)
        val released = expired.count { r ->
            tx.execute {
                reservations.transition(r.id, ReservationStatus.HELD, ReservationStatus.RELEASED, ReleaseReason.EXPIRED)
                    .also { if (it) inventory.releaseHold(r.roomTypeId, r.stayDate) }
            }!!
        }
        if (released > 0) log.info("Released {} expired holds", released)
        meters.counter("farelock.holds.expired").increment(released.toDouble())
        return released
    }

    /**
     * Finishes payments whose outcome we never learned (timeout, lost response, crash, pending).
     * Asks the provider what happened and applies that. Inventory is only released once the
     * provider confirms a decline, or after a grace period in which it has no record of the charge.
     */
    fun reconcilePayments(): Map<String, Int> {
        val now = clock.instant()
        val stuck = reservations.findOlderThan(ReservationStatus.PAYMENT_PENDING, "updated_at", now.minus(reconcileAfter), 100)
        val results = stuck.map { r ->
            val outcome = provider.lookup(r.id.toString())
            val result = when {
                outcome == ChargeOutcome.APPROVED -> if (payments.settle(r.id, outcome)) "confirmed" else "skipped"
                outcome == ChargeOutcome.DECLINED -> if (payments.settle(r.id, outcome)) "released" else "skipped"
                outcome == null && r.updatedAt.isBefore(now.minus(notFoundAfter)) ->
                    if (tx.execute { payments.release(r, ReleaseReason.PAYMENT_NOT_FOUND) }!!) "released" else "skipped"
                else -> "still_pending"
            }
            meters.counter("farelock.reconciliation", "result", result).increment()
            log.info("Reconciled reservation {}: provider={} result={}", r.id, outcome, result)
            result
        }
        return results.groupingBy { it }.eachCount()
    }

    /**
     * Retries refunds that were recorded but not confirmed by the provider (timeout, provider
     * error, crash between the two steps). The refund key makes each retry safe.
     */
    fun retryRefunds(): Map<String, Int> {
        val pending = reservations.findPendingRefunds(clock.instant().minus(reconcileAfter), 100)
        val results = pending.map { r ->
            val result = try {
                cancellations.refund(r)
                "refunded"
            } catch (e: Exception) {
                log.warn("Refund retry failed for reservation {}: {}", r.id, e.message)
                "refund_failed"
            }
            meters.counter("farelock.reconciliation", "result", result).increment()
            result
        }
        return results.groupingBy { it }.eachCount()
    }
}

/** Runs the jobs on a timer. Disabled in tests, which call the jobs directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty("farelock.jobs.enabled", havingValue = "true", matchIfMissing = true)
class JobSchedule(private val jobs: BackgroundJobs) {
    @Scheduled(initialDelay = 0, fixedDelayString = "\${farelock.jobs.inventory-interval:1h}")
    fun stockInventory() {
        jobs.extendInventory()
    }

    @Scheduled(fixedDelayString = "\${farelock.jobs.interval:10s}")
    fun run() {
        jobs.expireHolds()
        jobs.reconcilePayments()
        jobs.retryRefunds()
    }
}

/** Manual trigger for demos and load tests. In a real deployment this would not be publicly reachable. */
@RestController
class JobController(private val jobs: BackgroundJobs) {
    @PostMapping("/internal/jobs/run")
    fun run() = mapOf(
        "inventoryRowsAdded" to jobs.extendInventory(),
        "holdsExpired" to jobs.expireHolds(),
        "reconciliation" to jobs.reconcilePayments(),
        "refunds" to jobs.retryRefunds(),
    )
}
