package com.farelock.pricing

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class RoomRate(
    val roomTypeId: Long,
    val hotelId: Long,
    val basePrice: BigDecimal,
    val currency: String,
)

data class RoomPrice(
    val roomTypeId: Long,
    val amount: BigDecimal,
    val currency: String,
)

data class HotelPrices(
    val hotelId: Long,
    val stayDate: LocalDate,
    val prices: List<RoomPrice>,
)

data class Quote(
    val id: UUID,
    val roomTypeId: Long,
    val hotelId: Long,
    val stayDate: LocalDate,
    val amount: BigDecimal,
    val protectionFee: BigDecimal,
    val currency: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class QuoteResponse(
    val id: UUID,
    val roomTypeId: Long,
    val hotelId: Long,
    val stayDate: LocalDate,
    val amount: BigDecimal,
    val protectionFee: BigDecimal, // price of optional free cancellation, locked with the room price
    val currency: String,
    val expiresAt: Instant,
    val expired: Boolean,
)
