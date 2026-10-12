package com.farelock.pricing

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class PricingRepository(private val jdbc: JdbcClient) {

    fun findRatesForHotel(hotelId: Long): List<RoomRate> =
        jdbc.sql("SELECT * FROM room_rates WHERE hotel_id = ? ORDER BY base_price")
            .param(hotelId)
            .query { rs, _ -> rs.toRoomRate() }
            .list()

    fun findRate(roomTypeId: Long): RoomRate? =
        jdbc.sql("SELECT * FROM room_rates WHERE room_type_id = ?")
            .param(roomTypeId)
            .query { rs, _ -> rs.toRoomRate() }
            .optional()
            .orElse(null)

    fun insertQuote(q: Quote) {
        jdbc.sql(
            """
            INSERT INTO quotes (id, room_type_id, hotel_id, stay_date, amount, protection_fee, currency, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
        )
            .params(q.id, q.roomTypeId, q.hotelId, q.stayDate, q.amount, q.protectionFee, q.currency,
                Timestamp.from(q.createdAt), Timestamp.from(q.expiresAt))
            .update()
    }

    fun findQuote(id: UUID): Quote? =
        jdbc.sql("SELECT * FROM quotes WHERE id = ?")
            .param(id)
            .query { rs, _ ->
                Quote(
                    id = rs.getObject("id", UUID::class.java),
                    roomTypeId = rs.getLong("room_type_id"),
                    hotelId = rs.getLong("hotel_id"),
                    stayDate = rs.getDate("stay_date").toLocalDate(),
                    amount = rs.getBigDecimal("amount"),
                    protectionFee = rs.getBigDecimal("protection_fee"),
                    currency = rs.getString("currency"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                )
            }
            .optional()
            .orElse(null)

    private fun ResultSet.toRoomRate() = RoomRate(
        roomTypeId = getLong("room_type_id"),
        hotelId = getLong("hotel_id"),
        basePrice = getBigDecimal("base_price"),
        currency = getString("currency"),
    )
}
