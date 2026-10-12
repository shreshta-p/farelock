package com.farelock.reservation.booking

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ReservationStatus { HELD, PAYMENT_PENDING, CONFIRMED, RELEASED, CANCELLED }

enum class ReleaseReason { EXPIRED, DECLINED, PAYMENT_NOT_FOUND }

/** NONE = nothing to give back; PENDING = owed but the provider hasn't confirmed; REFUNDED = paid back. */
enum class RefundStatus { NONE, PENDING, REFUNDED }

data class Reservation(
    val id: UUID,
    val userId: Long,
    val hotelId: Long,
    val hotelName: String,
    val hotelTimezone: String,
    val roomTypeId: Long,
    val roomName: String,
    val stayDate: LocalDate,
    val guestName: String,
    val amount: BigDecimal,          // room price
    val protectionFee: BigDecimal?,  // null = no cancellation protection
    val currency: String,
    val status: ReservationStatus,
    val releaseReason: ReleaseReason?,
    val holdExpiresAt: Instant,
    val cancelDeadline: Instant,
    val cancelledAt: Instant?,
    val refundAmount: BigDecimal?,
    val refundStatus: RefundStatus?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** What the guest is charged: the room plus the protection fee, if bought. Also sent in the JSON. */
    val total: BigDecimal get() = amount + (protectionFee ?: BigDecimal.ZERO)
}

data class NewReservation(
    val id: UUID,
    val userId: Long,
    val hotelId: Long,
    val roomTypeId: Long,
    val stayDate: LocalDate,
    val guestName: String,
    val quoteId: UUID,
    val amount: BigDecimal,
    val protectionFee: BigDecimal?,
    val currency: String,
    val holdExpiresAt: Instant,
    val cancelDeadline: Instant,
)

@Repository
class ReservationRepository(private val jdbc: JdbcClient) {

    fun insert(r: NewReservation) {
        jdbc.sql(
            """
            INSERT INTO reservations (id, user_id, hotel_id, room_type_id, stay_date, guest_name, quote_id,
                                      amount, protection_fee, currency, status, hold_expires_at, cancel_deadline)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HELD', ?, ?)
            """
        )
            .param(r.id).param(r.userId).param(r.hotelId).param(r.roomTypeId).param(r.stayDate)
            .param(r.guestName).param(r.quoteId).param(r.amount).param(r.protectionFee).param(r.currency)
            .param(Timestamp.from(r.holdExpiresAt)).param(Timestamp.from(r.cancelDeadline))
            .update()
    }

    fun find(id: UUID): Reservation? =
        jdbc.sql("$SELECT WHERE r.id = ?").param(id)
            .query { rs, _ -> rs.toReservation() }
            .optional().orElse(null)

    fun findForUser(userId: Long): List<Reservation> =
        jdbc.sql("$SELECT WHERE r.user_id = ? ORDER BY r.created_at DESC").param(userId)
            .query { rs, _ -> rs.toReservation() }
            .list()

    /**
     * Moves a reservation between states only if it is still in the expected state.
     * Returns false if another request or job got there first, which makes every
     * transition safe to attempt twice.
     */
    fun transition(id: UUID, from: ReservationStatus, to: ReservationStatus, reason: ReleaseReason? = null): Boolean =
        jdbc.sql("UPDATE reservations SET status = ?, release_reason = ?, updated_at = now() WHERE id = ? AND status = ?")
            .param(to.name).param(reason?.name).param(id).param(from.name)
            .update() == 1

    /** HELD -> PAYMENT_PENDING, but only while the hold has not expired. */
    fun startPayment(id: UUID, now: Instant): Boolean =
        jdbc.sql(
            """
            UPDATE reservations SET status = 'PAYMENT_PENDING', updated_at = now()
            WHERE id = ? AND status = 'HELD' AND hold_expires_at > ?
            """
        ).params(id, Timestamp.from(now)).update() == 1

    /**
     * HELD or CONFIRMED -> CANCELLED, recording the refund owed (null = none). The deadline is
     * re-checked in the same statement, so a request that passed the check a moment too early
     * still cannot cancel after check-in day has started.
     */
    fun cancel(id: UUID, from: ReservationStatus, refundAmount: BigDecimal?, now: Instant): Boolean =
        jdbc.sql(
            """
            UPDATE reservations
            SET status = 'CANCELLED', cancelled_at = ?, refund_amount = ?, refund_status = ?, updated_at = now()
            WHERE id = ? AND status = ? AND cancel_deadline > ?
            """
        )
            .param(Timestamp.from(now)).param(refundAmount)
            .param(if (refundAmount == null) RefundStatus.NONE.name else RefundStatus.PENDING.name)
            .param(id).param(from.name).param(Timestamp.from(now))
            .update() == 1

    /** PENDING -> REFUNDED once the provider has the refund. Conditional, so repeating it is harmless. */
    fun markRefunded(id: UUID): Boolean =
        jdbc.sql("UPDATE reservations SET refund_status = 'REFUNDED', updated_at = now() WHERE id = ? AND refund_status = 'PENDING'")
            .param(id).update() == 1

    /** Cancellations whose refund the provider has not confirmed, untouched since [cutoff]. */
    fun findPendingRefunds(cutoff: Instant, limit: Int): List<Reservation> =
        jdbc.sql("$SELECT WHERE r.refund_status = 'PENDING' AND r.updated_at < ? ORDER BY r.updated_at LIMIT ?")
            .params(Timestamp.from(cutoff), limit)
            .query { rs, _ -> rs.toReservation() }
            .list()

    /**
     * Unfinished reservations for the background jobs, oldest first. No row locks: the jobs
     * change rows only through [transition], so two runs racing on one row cannot both win.
     */
    fun findOlderThan(status: ReservationStatus, column: String, cutoff: Instant, limit: Int): List<Reservation> {
        require(column in setOf("hold_expires_at", "updated_at")) // never interpolate untrusted input
        return jdbc.sql("$SELECT WHERE r.status = ? AND r.$column < ? ORDER BY r.$column LIMIT ?")
            .params(status.name, Timestamp.from(cutoff), limit)
            .query { rs, _ -> rs.toReservation() }
            .list()
    }

    private fun ResultSet.toReservation() = Reservation(
        id = getObject("id", UUID::class.java),
        userId = getLong("user_id"),
        hotelId = getLong("hotel_id"),
        hotelName = getString("hotel_name"),
        hotelTimezone = getString("hotel_timezone"),
        roomTypeId = getLong("room_type_id"),
        roomName = getString("room_name"),
        stayDate = getDate("stay_date").toLocalDate(),
        guestName = getString("guest_name"),
        amount = getBigDecimal("amount"),
        protectionFee = getBigDecimal("protection_fee"),
        currency = getString("currency"),
        status = ReservationStatus.valueOf(getString("status")),
        releaseReason = getString("release_reason")?.let { ReleaseReason.valueOf(it) },
        holdExpiresAt = getTimestamp("hold_expires_at").toInstant(),
        cancelDeadline = getTimestamp("cancel_deadline").toInstant(),
        cancelledAt = getTimestamp("cancelled_at")?.toInstant(),
        refundAmount = getBigDecimal("refund_amount"),
        refundStatus = getString("refund_status")?.let { RefundStatus.valueOf(it) },
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    private companion object {
        const val SELECT = """
            SELECT r.*, h.name AS hotel_name, h.timezone AS hotel_timezone, t.name AS room_name
            FROM reservations r
            JOIN hotels h ON h.id = r.hotel_id
            JOIN room_types t ON t.id = r.room_type_id
        """
    }
}
