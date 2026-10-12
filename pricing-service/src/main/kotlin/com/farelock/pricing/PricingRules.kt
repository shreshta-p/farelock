package com.farelock.pricing

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * The whole pricing "engine": two multipliers on top of the seeded base rate.
 *  - Friday and Saturday nights cost 15% more.
 *  - Stays starting within the next 7 days cost 10% more (last-minute demand).
 */
object PricingRules {
    private val WEEKEND_MULTIPLIER = BigDecimal("1.15")
    private val LAST_MINUTE_MULTIPLIER = BigDecimal("1.10")
    private const val LAST_MINUTE_DAYS = 7
    private val PROTECTION_RATE = BigDecimal("0.10")

    fun nightlyPrice(basePrice: BigDecimal, currency: String, stayDate: LocalDate, today: LocalDate): BigDecimal {
        var price = basePrice
        if (stayDate.dayOfWeek == DayOfWeek.FRIDAY || stayDate.dayOfWeek == DayOfWeek.SATURDAY) {
            price = price.multiply(WEEKEND_MULTIPLIER)
        }
        if (ChronoUnit.DAYS.between(today, stayDate) < LAST_MINUTE_DAYS) {
            price = price.multiply(LAST_MINUTE_MULTIPLIER)
        }
        return roundToCurrency(price, currency)
    }

    /** Optional free-cancellation protection: 10% of the room price, not refundable itself. */
    fun protectionFee(roomPrice: BigDecimal, currency: String): BigDecimal =
        roundToCurrency(roomPrice.multiply(PROTECTION_RATE), currency)

    // Round to the currency's minor unit (2 for EUR/USD, 0 for JPY).
    // Banker's rounding avoids a systematic upward bias across many prices.
    private fun roundToCurrency(amount: BigDecimal, currency: String): BigDecimal =
        amount.setScale(Currency.getInstance(currency).defaultFractionDigits, RoundingMode.HALF_EVEN)
}
