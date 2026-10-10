package com.farelock.reservation.booking

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate

/**
 * Every inventory change is a single conditional UPDATE. Postgres locks the row for the
 * duration of the statement, so concurrent requests are applied one at a time and the
 * WHERE clause is always evaluated against the latest committed values. No SELECT-then-UPDATE
 * race is possible, and the table's CHECK constraint is a second line of defence.
 */
@Repository
class InventoryRepository(private val jdbc: JdbcClient) {

    /** Takes one room out of the free pool. Returns false when none are left. */
    fun tryHold(roomTypeId: Long, stayDate: LocalDate): Boolean =
        jdbc.sql(
            """
            UPDATE room_inventory SET held = held + 1
            WHERE room_type_id = ? AND stay_date = ? AND held + booked < total
            """
        ).params(roomTypeId, stayDate).update() == 1

    /** Puts a held room back (hold expired, or payment declined). */
    fun releaseHold(roomTypeId: Long, stayDate: LocalDate) {
        check(
            jdbc.sql("UPDATE room_inventory SET held = held - 1 WHERE room_type_id = ? AND stay_date = ? AND held > 0")
                .params(roomTypeId, stayDate).update() == 1
        ) { "No hold to release for room type $roomTypeId on $stayDate" }
    }

    /** Payment succeeded: the held room becomes a booked room. */
    fun convertHoldToBooking(roomTypeId: Long, stayDate: LocalDate) {
        check(
            jdbc.sql("UPDATE room_inventory SET held = held - 1, booked = booked + 1 WHERE room_type_id = ? AND stay_date = ? AND held > 0")
                .params(roomTypeId, stayDate).update() == 1
        ) { "No hold to convert for room type $roomTypeId on $stayDate" }
    }
}
