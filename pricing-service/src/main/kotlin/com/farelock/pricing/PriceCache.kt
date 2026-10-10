package com.farelock.pricing

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Read-through cache for hotel prices in Redis.
 *
 * Redis is an optimisation, never a dependency: every failure is logged, counted,
 * and turned into a cache miss so the caller falls back to Postgres.
 * After a failure the cache is skipped for a few seconds, so an outage costs one
 * fast failure every few seconds instead of a timeout on every request.
 */
@Component
class PriceCache(
    private val redis: StringRedisTemplate,
    private val json: JsonMapper,
    private val meters: MeterRegistry,
    private val clock: Clock,
    @Value("\${farelock.cache.enabled:true}") private val enabled: Boolean,
    @Value("\${farelock.cache.ttl:60s}") private val ttl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var skipUntil: Instant = Instant.MIN

    fun get(hotelId: Long, stayDate: LocalDate): HotelPrices? {
        if (!isUsable()) return null
        return try {
            val cached = redis.opsForValue().get(key(hotelId, stayDate))
            if (cached == null) {
                count("miss")
                null
            } else {
                count("hit")
                json.readValue(cached, HotelPrices::class.java)
            }
        } catch (e: Exception) {
            onFailure("read", e)
            null
        }
    }

    fun put(prices: HotelPrices) {
        if (!isUsable()) return
        try {
            redis.opsForValue().set(key(prices.hotelId, prices.stayDate), json.writeValueAsString(prices), ttl)
        } catch (e: Exception) {
            onFailure("write", e)
        }
    }

    private fun isUsable() = enabled && clock.instant().isAfter(skipUntil)

    private fun onFailure(operation: String, e: Exception) {
        count("error")
        skipUntil = clock.instant().plusSeconds(5)
        log.warn("Redis {} failed, using database for the next 5s: {}", operation, e.message)
    }

    private fun count(result: String) = meters.counter("farelock.cache", "result", result).increment()

    // "v1" lets us change the cached JSON shape later without reading old entries.
    private fun key(hotelId: Long, stayDate: LocalDate) = "prices:v1:$hotelId:$stayDate"
}
