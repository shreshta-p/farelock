package com.farelock.reservation

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Ids far away from the demo seed data, so tests never touch it. */
object TestHotel {
    const val ID = 9001L
    const val ROOM_TYPE_ID = 900101L
}

/** Base class: the full app on a random port, backed by a real Postgres container. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Jobs are triggered explicitly by tests; short payment timeout keeps timeout tests fast.
    properties = ["farelock.jobs.enabled=false", "farelock.payment.timeout=500ms", "farelock.payment.provider-slow-response=3s"],
)
@Import(TestcontainersConfiguration::class)
abstract class IntegrationTest {
    @LocalServerPort var port = 0
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: JsonMapper

    val stayDate: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(14)

    fun client() = TestClient(port, json)

    /** Registers a fresh user and returns a client that is logged in as them. */
    fun loggedInClient(): TestClient = client().also {
        val email = "user-${UUID.randomUUID()}@example.test"
        val res = it.post("/api/auth/register", """{"email":"$email","password":"correct horse","fullName":"Test Guest"}""")
        check(res.status == 201) { "register failed: ${res.body}" }
    }

    /** One test hotel with one room type and [rooms] rooms for [stayDate]. */
    fun seedHotel(rooms: Int) {
        jdbc.sql(
            """
            INSERT INTO hotels (id, name, city, country, address, description)
            VALUES (${TestHotel.ID}, 'Test Hotel', 'Testville', 'Testland', '1 Test St', 'For tests')
            """
        ).update()
        jdbc.sql(
            """
            INSERT INTO room_types (id, hotel_id, name, description, bed, size_sqm, max_guests)
            VALUES (${TestHotel.ROOM_TYPE_ID}, ${TestHotel.ID}, 'Test Room', 'A room', 'King', 25, 2)
            """
        ).update()
        jdbc.sql("INSERT INTO room_inventory (room_type_id, stay_date, total) VALUES (?, ?, ?)")
            .params(TestHotel.ROOM_TYPE_ID, stayDate, rooms).update()
    }

    fun reservationBody(quoteId: UUID, guest: String = "Ada Lovelace", protection: Boolean = false) =
        """{"quoteId":"$quoteId","roomTypeId":${TestHotel.ROOM_TYPE_ID},"stayDate":"$stayDate","guestName":"$guest","cancellationProtection":$protection}"""

    fun inventory(): Pair<Int, Int> =
        jdbc.sql("SELECT held, booked FROM room_inventory WHERE room_type_id = ? AND stay_date = ?")
            .params(TestHotel.ROOM_TYPE_ID, stayDate)
            .query { rs, _ -> rs.getInt("held") to rs.getInt("booked") }
            .single()

    fun countRows(sql: String): Int = jdbc.sql(sql).query(Int::class.java).single()

    @BeforeEach
    fun cleanDatabase() {
        FakePricing.down = false
        jdbc.sql("TRUNCATE payment_provider.charges, idempotency_keys, reservations, sessions, users CASCADE").update()
        jdbc.sql("DELETE FROM room_inventory WHERE room_type_id >= 900000").update()
        jdbc.sql("DELETE FROM room_types WHERE id >= 900000").update()
        jdbc.sql("DELETE FROM hotels WHERE id >= 9000").update()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun pricingUrl(registry: DynamicPropertyRegistry) {
            registry.add("farelock.pricing.base-url") { FakePricing.url }
        }
    }
}
