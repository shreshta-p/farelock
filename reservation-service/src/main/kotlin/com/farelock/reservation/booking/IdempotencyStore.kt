package com.farelock.reservation.booking

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The exact HTTP status and JSON body we answered with, so a retry gets the identical answer. */
data class StoredResponse(val status: Int, val body: String)

sealed interface Claim {
    /** First time we see this key: go ahead and do the work. */
    data object Acquired : Claim
    /** Already done: send back the stored response. */
    data class Replay(val response: StoredResponse) : Claim
    /** Same key, different request body: a client bug, reject with 409. */
    data object Conflict : Claim
    /** Another request with this key is running right now. */
    data object InProgress : Claim
}

/**
 * Idempotency keys are stored in Postgres, not memory, so they work across restarts and
 * across multiple service instances. The primary key (user_id, idempotency_key) means two
 * concurrent requests with the same key cannot both insert: exactly one gets Acquired.
 */
@Component
class IdempotencyStore(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    // A request holds its key at most this long. Requests finish within a few seconds
    // (all remote calls have timeouts), so an older IN_PROGRESS row means the process
    // crashed mid-request and the key may be taken over.
    private val abandonedAfter = Duration.ofSeconds(60)

    fun claim(userId: Long, key: String, requestHash: String): Claim {
        val inserted = jdbc.sql(
            """
            INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status)
            VALUES (?, ?, ?, 'IN_PROGRESS') ON CONFLICT DO NOTHING
            """
        ).params(userId, key, requestHash).update()
        if (inserted == 1) return Claim.Acquired

        val row = jdbc.sql(
            """
            SELECT request_hash, status, response_status, response_body, updated_at
            FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?
            """
        ).params(userId, key).query { rs, _ ->
            ExistingKey(
                requestHash = rs.getString("request_hash"),
                completed = rs.getString("status") == "COMPLETED",
                response = rs.getString("response_body")?.let { StoredResponse(rs.getInt("response_status"), it) },
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }.optional().orElse(null) ?: return Claim.InProgress // released a moment ago; client can retry

        return when {
            row.requestHash != requestHash -> Claim.Conflict.also { count("conflict") }
            row.completed -> Claim.Replay(row.response!!).also { count("replay") }
            row.updatedAt.isBefore(clock.instant().minus(abandonedAfter)) && takeOver(userId, key, row.updatedAt) -> Claim.Acquired
            else -> Claim.InProgress.also { count("in_progress") }
        }
    }

    /** Records the final response. Called inside the same transaction as the business write. */
    fun complete(userId: Long, key: String, response: StoredResponse) {
        jdbc.sql(
            """
            UPDATE idempotency_keys
            SET status = 'COMPLETED', response_status = ?, response_body = ?, updated_at = now()
            WHERE user_id = ? AND idempotency_key = ?
            """
        ).params(response.status, response.body, userId, key).update()
    }

    /** Forgets an unfinished key after a temporary failure (e.g. pricing down), so the client can retry. */
    fun release(userId: Long, key: String) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'")
            .params(userId, key).update()
    }

    // Conditional on updated_at so that only one of several retries can take over.
    private fun takeOver(userId: Long, key: String, seenUpdatedAt: Instant) =
        jdbc.sql(
            "UPDATE idempotency_keys SET updated_at = now() WHERE user_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND updated_at = ?"
        ).params(userId, key, Timestamp.from(seenUpdatedAt)).update() == 1

    private fun count(result: String) = meters.counter("farelock.idempotency", "result", result).increment()

    private data class ExistingKey(
        val requestHash: String,
        val completed: Boolean,
        val response: StoredResponse?,
        val updatedAt: Instant,
    )
}
