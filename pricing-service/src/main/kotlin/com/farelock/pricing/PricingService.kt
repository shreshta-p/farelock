package com.farelock.pricing

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@Service
class PricingService(
    private val repository: PricingRepository,
    private val cache: PriceCache,
    private val clock: Clock,
    @Value("\${farelock.quote.ttl:15m}") private val quoteTtl: Duration,
    @Value("\${farelock.booking-window-days:90}") private val bookingWindowDays: Long,
) {

    fun pricesFor(hotelId: Long, stayDate: LocalDate): HotelPrices {
        validateStayDate(stayDate)
        cache.get(hotelId, stayDate)?.let { return it }

        val rates = repository.findRatesForHotel(hotelId)
        if (rates.isEmpty()) throw NotFoundException("No rates for hotel $hotelId")
        val prices = HotelPrices(hotelId, stayDate, rates.map { priceOf(it, stayDate) })
        cache.put(prices)
        return prices
    }

    fun createQuote(roomTypeId: Long, stayDate: LocalDate): QuoteResponse {
        validateStayDate(stayDate)
        val rate = repository.findRate(roomTypeId) ?: throw NotFoundException("Unknown room type $roomTypeId")
        val price = priceOf(rate, stayDate)
        val now = clock.instant()
        val quote = Quote(
            id = UUID.randomUUID(),
            roomTypeId = roomTypeId,
            hotelId = rate.hotelId,
            stayDate = stayDate,
            amount = price.amount,
            currency = price.currency,
            createdAt = now,
            expiresAt = now.plus(quoteTtl),
        )
        repository.insertQuote(quote)
        return toResponse(quote)
    }

    fun getQuote(id: UUID): QuoteResponse =
        toResponse(repository.findQuote(id) ?: throw NotFoundException("Quote $id not found"))

    private fun priceOf(rate: RoomRate, stayDate: LocalDate) = RoomPrice(
        roomTypeId = rate.roomTypeId,
        amount = PricingRules.nightlyPrice(rate.basePrice, rate.currency, stayDate, LocalDate.now(clock)),
        currency = rate.currency,
    )

    private fun toResponse(q: Quote) = QuoteResponse(
        id = q.id,
        roomTypeId = q.roomTypeId,
        hotelId = q.hotelId,
        stayDate = q.stayDate,
        amount = q.amount,
        currency = q.currency,
        expiresAt = q.expiresAt,
        expired = !clock.instant().isBefore(q.expiresAt),
    )

    private fun validateStayDate(stayDate: LocalDate) {
        val today = LocalDate.now(clock)
        if (stayDate.isBefore(today) || stayDate.isAfter(today.plusDays(bookingWindowDays))) {
            throw BadRequestException("Stay date must be between today and $bookingWindowDays days from now")
        }
    }
}
