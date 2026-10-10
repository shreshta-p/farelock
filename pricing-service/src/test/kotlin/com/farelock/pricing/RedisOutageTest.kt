package com.farelock.pricing

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.containers.GenericContainer
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stops the real Redis container mid-test: pricing must keep answering from Postgres. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@DirtiesContext
class RedisOutageTest {
    @Autowired lateinit var pricing: PricingService
    @Autowired lateinit var redisContainer: GenericContainer<*>
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var meters: MeterRegistry

    @Test
    fun `prices still work when redis goes down`() {
        jdbc.sql("DELETE FROM quotes").update()
        jdbc.sql("DELETE FROM room_rates").update()
        jdbc.sql("INSERT INTO room_rates VALUES (21, 2, 150.00, 'USD')").update()
        val date = LocalDate.now(ZoneOffset.UTC).plusDays(20)
        val healthy = pricing.pricesFor(2, date)

        redisContainer.stop()

        val started = System.nanoTime()
        val degraded = pricing.pricesFor(2, date)
        val again = pricing.pricesFor(2, date)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(healthy, degraded)
        assertEquals(healthy, again)
        assertTrue(meters.counter("farelock.cache", "result", "error").count() >= 1.0)
        // Fail-fast + skip window: requests must not wait out long Redis timeouts.
        assertTrue(elapsedMs < 2_000, "degraded requests took ${elapsedMs}ms")
    }
}
