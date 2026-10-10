package com.farelock.reservation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogTest : IntegrationTest() {

    @Test
    fun `search finds hotels by city with availability and a from-price`() {
        seedHotel(rooms = 4)

        val res = client().get("/api/hotels?destination=testville&date=$stayDate")

        assertEquals(200, res.status)
        val hit = res.body.first { it["hotel"]["id"].asLong() == TestHotel.ID }
        assertEquals(4, hit["roomsAvailable"].asInt())
        assertEquals("EUR", hit["fromPrice"]["currency"].asString())
    }

    @Test
    fun `hotel detail combines rooms, availability and prices`() {
        seedHotel(rooms = 2)

        val res = client().get("/api/hotels/${TestHotel.ID}?date=$stayDate")

        assertTrue(res.body["pricesAvailable"].asBoolean())
        val room = res.body["rooms"][0]
        assertEquals(2, room["available"].asInt())
        assertEquals(180.0, room["price"]["amount"].asDouble())
    }

    @Test
    fun `hotel detail still loads when pricing is down`() {
        seedHotel(rooms = 2)
        FakePricing.down = true

        val res = client().get("/api/hotels/${TestHotel.ID}?date=$stayDate")

        assertEquals(200, res.status)
        assertFalse(res.body["pricesAvailable"].asBoolean())
        assertTrue(res.body["rooms"][0]["price"].isNull)
    }

    @Test
    fun `unknown hotels and out-of-range dates are rejected`() {
        assertEquals(404, client().get("/api/hotels/123456?date=$stayDate").status)
        assertEquals("INVALID_DATE", client().get("/api/hotels?date=2001-01-01").code)
    }
}
