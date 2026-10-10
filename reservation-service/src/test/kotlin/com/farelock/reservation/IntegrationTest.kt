package com.farelock.reservation

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Base class: the full app on a random port, backed by a real Postgres container. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
abstract class IntegrationTest {
    @LocalServerPort var port = 0
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: JsonMapper

    fun client() = TestClient(port, json)

    /** Registers a fresh user and returns a client that is logged in as them. */
    fun loggedInClient(): TestClient = client().also {
        val email = "user-${UUID.randomUUID()}@example.test"
        val res = it.post("/api/auth/register", """{"email":"$email","password":"correct horse","fullName":"Test Guest"}""")
        check(res.status == 201) { "register failed: ${res.body}" }
    }

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("TRUNCATE payment_provider.charges, idempotency_keys, reservations, sessions, users CASCADE").update()
    }
}
