package com.farelock.pricing

import io.lettuce.core.ClientOptions
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
class PricingServiceApplication {

    /** Injected everywhere "now" matters, so tests can control time. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * When Redis is down, fail commands immediately instead of queueing them
     * until the timeout. PriceCache then falls back to the database.
     */
    @Bean
    fun redisFailFast() = LettuceClientConfigurationBuilderCustomizer { builder ->
        builder.clientOptions(
            ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build()
        )
    }
}

fun main(args: Array<String>) {
    runApplication<PricingServiceApplication>(*args)
}
