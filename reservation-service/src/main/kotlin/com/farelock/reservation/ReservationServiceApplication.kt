package com.farelock.reservation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
class ReservationServiceApplication {

    /** Injected everywhere "now" matters, so tests can control time. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<ReservationServiceApplication>(*args)
}
