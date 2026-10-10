package com.farelock.reservation.catalog

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** Public browsing endpoints. `suspend` lets Spring MVC run them as coroutines. */
@RestController
@RequestMapping("/api")
class CatalogController(private val catalog: CatalogService) {

    @GetMapping("/destinations")
    fun destinations() = catalog.destinations()

    @GetMapping("/hotels")
    suspend fun search(@RequestParam(required = false) destination: String?, @RequestParam date: LocalDate) =
        catalog.search(destination, date)

    @GetMapping("/hotels/{id}")
    suspend fun detail(@PathVariable id: Long, @RequestParam date: LocalDate) =
        catalog.detail(id, date)
}
