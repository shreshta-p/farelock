package com.farelock.reservation.booking

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ReservationStatus { HELD, PAYMENT_PENDING, CONFIRMED, RELEASED }

enum class ReleaseReason { EXPIRED, DECLINED, PAYMENT_NOT_FOUND }

data class Reservation(
    val id: UUID,
    val userId: Long,
    val hotelId: Long,
    val hotelName: String,
    val roomTypeId: Long,
    val roomName: String,
    val stayDate: LocalDate,
    val guestName: String,
    val amount: BigDecimal,
    val currency: String,
    val status: ReservationStatus,
    val releaseReason: ReleaseReason?,
    val holdExpiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NewReservation(
    val id: UUID,
    val userId: Long,
    val hotelId: Long,
    val roomTypeId: Long,
    val stayDate: LocalDate,
    val guestName: String,
    val quoteId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val holdExpiresAt: Instant,
)

@Repository
class ReservationRepository(private val jdbc: JdbcClient) {

    fun insert(r: NewReservation) {
        jdbc.sql(
            """
            INSERT INTO reservations (id, user_id, hotel_id, room_type_id, stay_date, guest_name,
                                      quote_id, amount, currency, status, hold_expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'HELD', ?)
            """
        )
            .params(r.id, r.userId, r.hotelId, r.roomTypeId, r.stayDate, r.guestName,
                r.quoteId, r.amount, r.currency, Timestamp.from(r.holdExpiresAt))
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
     * Locks a batch of reservations for a background job. SKIP LOCKED means two job runs
     * (or two service instances) never process the same row at the same time.
     * Must be called inside a transaction.
     */
    fun lockBatch(status: ReservationStatus, olderThanColumn: String, cutoff: Instant, limit: Int): List<Reservation> {
        require(olderThanColumn in setOf("hold_expires_at", "updated_at"))
        return jdbc.sql("$SELECT WHERE r.status = ? AND r.$olderThanColumn < ? ORDER BY r.$olderThanColumn LIMIT ? FOR UPDATE OF r SKIP LOCKED")
            .params(status.name, Timestamp.from(cutoff), limit)
            .query { rs, _ -> rs.toReservation() }
            .list()
    }

    private fun ResultSet.toReservation() = Reservation(
        id = getObject("id", UUID::class.java),
        userId = getLong("user_id"),
        hotelId = getLong("hotel_id"),
        hotelName = getString("hotel_name"),
        roomTypeId = getLong("room_type_id"),
        roomName = getString("room_name"),
        stayDate = getDate("stay_date").toLocalDate(),
        guestName = getString("guest_name"),
        amount = getBigDecimal("amount"),
        currency = getString("currency"),
        status = ReservationStatus.valueOf(getString("status")),
        releaseReason = getString("release_reason")?.let { ReleaseReason.valueOf(it) },
        holdExpiresAt = getTimestamp("hold_expires_at").toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    private companion object {
        const val SELECT = """
            SELECT r.*, h.name AS hotel_name, t.name AS room_name
            FROM reservations r
            JOIN hotels h ON h.id = r.hotel_id
            JOIN room_types t ON t.id = r.room_type_id
        """
    }
}
