package com.farelock.pricing

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

class PricingRulesTest {
    private val today = LocalDate.of(2026, 3, 2) // a Monday
    private val base = BigDecimal("200.00")

    @Test
    fun `weekday far in advance pays the base rate`() {
        assertEquals(BigDecimal("200.00"), PricingRules.nightlyPrice(base, "EUR", LocalDate.of(2026, 3, 18), today))
    }

    @Test
    fun `friday and saturday nights cost 15 percent more`() {
        assertEquals(BigDecimal("230.00"), PricingRules.nightlyPrice(base, "EUR", LocalDate.of(2026, 3, 20), today))
        assertEquals(BigDecimal("230.00"), PricingRules.nightlyPrice(base, "EUR", LocalDate.of(2026, 3, 21), today))
    }

    @Test
    fun `stays within 7 days cost 10 percent more`() {
        assertEquals(BigDecimal("220.00"), PricingRules.nightlyPrice(base, "EUR", LocalDate.of(2026, 3, 4), today))
        assertEquals(BigDecimal("200.00"), PricingRules.nightlyPrice(base, "EUR", LocalDate.of(2026, 3, 9), today))
    }

    @Test
    fun `both rules combine and round to cents`() {
        // 199.99 * 1.15 * 1.10 = 252.98735 -> 252.99
        assertEquals(BigDecimal("252.99"), PricingRules.nightlyPrice(BigDecimal("199.99"), "EUR", LocalDate.of(2026, 3, 6), today))
    }

    @Test
    fun `yen prices have no minor unit`() {
        // Friday: 30,999 * 1.15 = 35,648.85 -> 35,649 (no decimals for yen)
        assertEquals(BigDecimal("35649"), PricingRules.nightlyPrice(BigDecimal("30999"), "JPY", LocalDate.of(2026, 3, 20), today))
    }
}
