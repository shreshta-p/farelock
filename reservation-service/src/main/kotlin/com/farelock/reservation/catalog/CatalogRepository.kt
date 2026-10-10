package com.farelock.reservation.catalog

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate

data class Hotel(
    val id: Long,
    val name: String,
    val city: String,
    val country: String,
    val address: String,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val starRating: Int?,
    val description: String,
    val amenities: List<String>,
    val images: List<String>,
    val sourceUrl: String?,
)

data class RoomType(
    val id: Long,
    val hotelId: Long,
    val name: String,
    val description: String,
    val bed: String,
    val sizeSqm: Int,
    val maxGuests: Int,
    val image: String?,
)

data class RoomAvailability(val hotelId: Long, val roomTypeId: Long, val free: Int)

data class Destination(val city: String, val country: String, val hotelCount: Int)

@Repository
class CatalogRepository(private val jdbc: JdbcClient) {

    fun searchHotels(destination: String?): List<Hotel> {
        val pattern = "%${destination?.trim()?.lowercase() ?: ""}%"
        return jdbc.sql(
            """
            SELECT * FROM hotels
            WHERE listed AND (lower(city) LIKE ? OR lower(country) LIKE ? OR lower(name) LIKE ?)
            ORDER BY star_rating DESC NULLS LAST, name
            """
        )
            .params(pattern, pattern, pattern)
            .query { rs, _ -> rs.toHotel() }
            .list()
    }

    fun findHotel(id: Long): Hotel? =
        jdbc.sql("SELECT * FROM hotels WHERE id = ?").param(id)
            .query { rs, _ -> rs.toHotel() }
            .optional().orElse(null)

    fun roomTypes(hotelId: Long): List<RoomType> =
        jdbc.sql("SELECT * FROM room_types WHERE hotel_id = ? ORDER BY size_sqm").param(hotelId)
            .query { rs, _ -> rs.toRoomType() }
            .list()

    fun findRoomType(id: Long): RoomType? =
        jdbc.sql("SELECT * FROM room_types WHERE id = ?").param(id)
            .query { rs, _ -> rs.toRoomType() }
            .optional().orElse(null)

    /** Rooms still free per room type on one night, for the given hotels. */
    fun availability(hotelIds: List<Long>, stayDate: LocalDate): List<RoomAvailability> {
        if (hotelIds.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            SELECT r.hotel_id, i.room_type_id, i.total - i.held - i.booked AS free
            FROM room_inventory i JOIN room_types r ON r.id = i.room_type_id
            WHERE r.hotel_id = ANY(?) AND i.stay_date = ?
            """
        )
            .params(hotelIds.toTypedArray(), stayDate)
            .query { rs, _ -> RoomAvailability(rs.getLong("hotel_id"), rs.getLong("room_type_id"), rs.getInt("free")) }
            .list()
    }

    fun destinations(): List<Destination> =
        jdbc.sql("SELECT city, country, count(*) AS n FROM hotels WHERE listed GROUP BY city, country ORDER BY city")
            .query { rs, _ -> Destination(rs.getString("city"), rs.getString("country"), rs.getInt("n")) }
            .list()

    @Suppress("UNCHECKED_CAST")
    private fun ResultSet.toHotel() = Hotel(
        id = getLong("id"),
        name = getString("name"),
        city = getString("city"),
        country = getString("country"),
        address = getString("address"),
        latitude = getBigDecimal("latitude"),
        longitude = getBigDecimal("longitude"),
        starRating = getInt("star_rating").takeUnless { wasNull() },
        description = getString("description"),
        amenities = (getArray("amenities").array as Array<String>).toList(),
        images = (getArray("images").array as Array<String>).toList(),
        sourceUrl = getString("source_url"),
    )

    private fun ResultSet.toRoomType() = RoomType(
        id = getLong("id"),
        hotelId = getLong("hotel_id"),
        name = getString("name"),
        description = getString("description"),
        bed = getString("bed"),
        sizeSqm = getInt("size_sqm"),
        maxGuests = getInt("max_guests"),
        image = getString("image"),
    )
}
