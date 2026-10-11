package com.farelock.reservation.catalog

import com.farelock.reservation.common.ApiException
import com.farelock.reservation.common.io
import com.farelock.reservation.common.RequestIdFilter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class Money(val amount: BigDecimal, val currency: String)

data class RoomPrice(val roomTypeId: Long, val amount: BigDecimal, val currency: String)

data class HotelPrices(val hotelId: Long, val stayDate: LocalDate, val prices: List<RoomPrice>)

data class Quote(
    val id: UUID,
    val roomTypeId: Long,
    val hotelId: Long,
    val stayDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val expiresAt: Instant,
    val expired: Boolean,
)

/** HTTP client for the Pricing Service, with short timeouts so a slow pricing call can't pile up threads. */
@Component
class PricingClient(
    builder: RestClient.Builder,
    @Value("\${farelock.pricing.base-url:http://localhost:8081}") baseUrl: String,
    @Value("\${farelock.pricing.timeout:2s}") timeout: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val http = builder
        .baseUrl(baseUrl)
        // Pass our correlation id along so both services' logs share it.
        .requestInterceptor { request, body, execution ->
            MDC.get(RequestIdFilter.MDC_KEY)?.let { request.headers.set(RequestIdFilter.HEADER, it) }
            execution.execute(request, body)
        }
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build())
                .apply { setReadTimeout(timeout) }
        )
        .build()

    /** Prices for display only: if pricing is down, return null and let the page show "unavailable". */
    suspend fun prices(hotelId: Long, stayDate: LocalDate): HotelPrices? = io {
        try {
            http.get().uri("/api/prices?hotelId={h}&date={d}", hotelId, stayDate)
                .retrieve().body(HotelPrices::class.java)
        } catch (e: Exception) {
            log.warn("Prices unavailable for hotel {}: {}", hotelId, e.message)
            null
        }
    }

    /** A booking cannot proceed without its quote, so failures become clear API errors. */
    suspend fun quote(id: UUID): Quote = io {
        try {
            http.get().uri("/api/quotes/{id}", id).retrieve().body(Quote::class.java)!!
        } catch (e: HttpClientErrorException.NotFound) {
            throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "QUOTE_NOT_FOUND", "That price quote does not exist")
        } catch (e: Exception) {
            log.warn("Pricing service call failed: {}", e.message)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE", "Pricing is temporarily unavailable, please retry")
        }
    }
}
