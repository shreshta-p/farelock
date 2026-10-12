package com.farelock.reservation.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

enum class ChargeOutcome { APPROVED, DECLINED, PENDING }

/** Test payment tokens, chosen on the checkout page. No card data exists anywhere. */
object PaymentTokens {
    const val APPROVE = "tok_approve"
    const val DECLINE = "tok_decline"
    const val TIMEOUT = "tok_timeout"             // provider never answers, nothing is charged
    const val LOST_RESPONSE = "tok_lost_response" // provider charges, but the answer arrives too late
    const val UNKNOWN = "tok_unknown"             // provider accepts but the outcome is pending for a while
    val ALL = setOf(APPROVE, DECLINE, TIMEOUT, LOST_RESPONSE, UNKNOWN)
}

/**
 * A simulated external payment provider.
 *
 * It keeps its own records in the `payment_provider` schema and writes them in its own
 * transactions (REQUIRES_NEW), so a charge it records survives even if the reservation
 * service's own transaction later fails or the process crashes, just like a real provider.
 * Charges are keyed by an idempotency key, so the same key can never be charged twice.
 */
@Component
class PaymentProvider(
    private val jdbc: JdbcClient,
    txManager: PlatformTransactionManager,
    private val clock: Clock,
    @Value("\${farelock.payment.provider-slow-response:5s}") private val slowResponse: Duration,
    @Value("\${farelock.payment.pending-settles-after:20s}") private val pendingSettlesAfter: Duration,
) {
    private val tx = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** Blocking, like a real HTTP call to a provider. May be slow; callers must bound it with a timeout. */
    fun charge(key: String, amount: BigDecimal, currency: String, token: String): ChargeOutcome {
        lookup(key)?.let { return it } // already charged with this key: return the original outcome

        return when (token) {
            PaymentTokens.APPROVE -> record(key, amount, currency, ChargeOutcome.APPROVED)
            PaymentTokens.DECLINE -> record(key, amount, currency, ChargeOutcome.DECLINED)
            PaymentTokens.UNKNOWN -> record(key, amount, currency, ChargeOutcome.PENDING)
            PaymentTokens.LOST_RESPONSE -> {
                val outcome = record(key, amount, currency, ChargeOutcome.APPROVED)
                Thread.sleep(slowResponse.toMillis()) // the money moved, but the caller gives up waiting
                outcome
            }
            PaymentTokens.TIMEOUT -> {
                Thread.sleep(slowResponse.toMillis())
                throw IllegalStateException("Provider did not respond")
            }
            else -> throw IllegalArgumentException("Unknown payment token")
        }
    }

    /** "What happened to charge X?" Used by reconciliation. Null means the provider never saw it. */
    fun lookup(key: String): ChargeOutcome? = tx.execute {
        // Pending charges settle (as approved) after a while, like a slow bank authorisation.
        jdbc.sql(
            """
            UPDATE payment_provider.charges SET outcome = 'APPROVED', updated_at = now()
            WHERE idempotency_key = ? AND outcome = 'PENDING' AND created_at < ?
            """
        ).params(key, Timestamp.from(clock.instant().minus(pendingSettlesAfter))).update()

        jdbc.sql("SELECT outcome FROM payment_provider.charges WHERE idempotency_key = ?")
            .param(key)
            .query(String::class.java)
            .optional().map { ChargeOutcome.valueOf(it) }.orElse(null)
    }

    /**
     * Refunds [amount] of an approved charge. Like charges, refunds are keyed: repeating a key
     * returns without paying out again, so callers can retry after a timeout or crash.
     * Throws if the charge is unknown/not approved or the refunds would exceed what was charged.
     */
    fun refund(key: String, chargeKey: String, amount: BigDecimal, currency: String) {
        tx.execute {
            // FOR UPDATE locks this charge's row, so two different refunds of one charge are checked
            // one after the other and can't both pass the "not more than was charged" check.
            val charge = jdbc.sql("SELECT amount, outcome FROM payment_provider.charges WHERE idempotency_key = ? FOR UPDATE")
                .param(chargeKey)
                .query { rs, _ -> rs.getBigDecimal("amount") to rs.getString("outcome") }
                .optional().orElse(null)
            check(charge != null && charge.second == ChargeOutcome.APPROVED.name) { "No approved charge $chargeKey" }

            val alreadyDone = jdbc.sql("SELECT count(*) FROM payment_provider.refunds WHERE idempotency_key = ?")
                .param(key).query(Int::class.java).single() > 0
            if (!alreadyDone) {
                val refunded = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payment_provider.refunds WHERE charge_key = ?")
                    .param(chargeKey).query(BigDecimal::class.java).single()
                check(refunded + amount <= charge.first) { "Refund would exceed the charge $chargeKey" }
                jdbc.sql("INSERT INTO payment_provider.refunds (idempotency_key, charge_key, amount, currency) VALUES (?, ?, ?, ?)")
                    .params(key, chargeKey, amount, currency).update()
            }
        }
    }

    // ON CONFLICT DO NOTHING + re-read: if two calls race with one key, both see the first outcome.
    private fun record(key: String, amount: BigDecimal, currency: String, outcome: ChargeOutcome): ChargeOutcome =
        tx.execute {
            jdbc.sql(
                """
                INSERT INTO payment_provider.charges (idempotency_key, amount, currency, outcome)
                VALUES (?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING
                """
            ).params(key, amount, currency, outcome.name).update()
            jdbc.sql("SELECT outcome FROM payment_provider.charges WHERE idempotency_key = ?")
                .param(key).query(String::class.java).single()
                .let { ChargeOutcome.valueOf(it) }
        }!!
}
