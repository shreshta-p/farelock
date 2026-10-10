package com.farelock.reservation

import com.farelock.reservation.booking.Claim
import com.farelock.reservation.booking.IdempotencyStore
import com.farelock.reservation.booking.StoredResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class IdempotencyStoreTest : IntegrationTest() {
    @Autowired lateinit var store: IdempotencyStore

    private fun userId(): Long {
        loggedInClient()
        return jdbc.sql("SELECT max(id) FROM users").query(Long::class.java).single()
    }

    @Test
    fun `a key moves from acquired to in progress to replay`() {
        val user = userId()
        assertEquals(Claim.Acquired, store.claim(user, "k", "hash-1"))
        assertEquals(Claim.InProgress, store.claim(user, "k", "hash-1"))

        store.complete(user, "k", StoredResponse(201, """{"id":"r1"}"""))

        assertEquals(Claim.Replay(StoredResponse(201, """{"id":"r1"}""")), store.claim(user, "k", "hash-1"))
        assertEquals(Claim.Conflict, store.claim(user, "k", "hash-2"))
    }

    @Test
    fun `a key abandoned by a crashed request can be taken over after a minute`() {
        val user = userId()
        store.claim(user, "k", "hash-1")
        // Simulate: the process died 2 minutes ago while handling this key.
        jdbc.sql("UPDATE idempotency_keys SET updated_at = now() - interval '2 minutes'").update()

        assertEquals(Claim.Acquired, store.claim(user, "k", "hash-1"))
        // The takeover refreshed the timestamp, so a second retry has to wait again.
        assertEquals(Claim.InProgress, store.claim(user, "k", "hash-1"))
    }
}
