package com.farelock.pricing

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class CreateQuoteRequest(
    @field:NotNull @field:Positive val roomTypeId: Long?,
    @field:NotNull val stayDate: LocalDate?,
)

@RestController
@RequestMapping("/api")
class PricingController(private val pricing: PricingService) {

    @GetMapping("/prices")
    fun prices(@RequestParam hotelId: Long, @RequestParam date: LocalDate): HotelPrices =
        pricing.pricesFor(hotelId, date)

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    fun createQuote(@Valid @RequestBody request: CreateQuoteRequest): QuoteResponse =
        pricing.createQuote(request.roomTypeId!!, request.stayDate!!)

    @GetMapping("/quotes/{id}")
    fun getQuote(@PathVariable id: UUID): QuoteResponse = pricing.getQuote(id)
}
