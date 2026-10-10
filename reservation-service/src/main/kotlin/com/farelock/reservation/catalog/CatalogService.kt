package com.farelock.reservation.catalog

import com.farelock.reservation.common.ApiException
import com.farelock.reservation.common.io
import com.farelock.reservation.common.notFound
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

data class HotelSummary(
    val hotel: Hotel,
    val roomsAvailable: Int,
    val fromPrice: Money?, // null when pricing is unavailable
)

data class RoomOffer(
    val room: RoomType,
    val available: Int,
    val price: Money?,
)

data class HotelDetail(
    val hotel: Hotel,
    val stayDate: LocalDate,
    val rooms: List<RoomOffer>,
    val pricesAvailable: Boolean,
)

@Service
class CatalogService(
    private val repository: CatalogRepository,
    private val pricing: PricingClient,
    private val clock: Clock,
    @Value("\${farelock.booking-window-days:90}") private val bookingWindowDays: Long,
) {

    fun destinations() = repository.destinations()

    /** Hotels matching a destination, each with a "from" price fetched concurrently. */
    suspend fun search(destination: String?, stayDate: LocalDate): List<HotelSummary> = coroutineScope {
        validateStayDate(stayDate)
        val hotels = io { repository.searchHotels(destination) }
        val availability = io { repository.availability(hotels.map { it.id }, stayDate) }
        val free = availability.associate { it.roomTypeId to it.free }

        // One pricing call per hotel, all in flight at the same time instead of one after another.
        val prices = hotels.map { hotel -> async { pricing.prices(hotel.id, stayDate) } }.awaitAll()

        hotels.zip(prices) { hotel, hotelPrices ->
            val cheapestAvailable = hotelPrices?.prices
                ?.filter { (free[it.roomTypeId] ?: 0) > 0 }
                ?.minByOrNull { it.amount }
            HotelSummary(
                hotel = hotel,
                roomsAvailable = availability.filter { it.hotelId == hotel.id }.sumOf { it.free },
                fromPrice = cheapestAvailable?.let { Money(it.amount, it.currency) },
            )
        }
    }

    /** Hotel facts + availability (Postgres) and prices (Pricing Service), loaded concurrently. */
    suspend fun detail(hotelId: Long, stayDate: LocalDate): HotelDetail = coroutineScope {
        validateStayDate(stayDate)
        val catalog = async {
            io {
                val hotel = repository.findHotel(hotelId) ?: throw notFound("Hotel")
                Triple(hotel, repository.roomTypes(hotelId), repository.availability(listOf(hotelId), stayDate))
            }
        }
        val prices = async { pricing.prices(hotelId, stayDate) }

        val (hotel, rooms, availability) = catalog.await()
        val free = availability.associate { it.roomTypeId to it.free }
        val priceByRoom = prices.await()?.prices?.associateBy { it.roomTypeId }
        HotelDetail(
            hotel = hotel,
            stayDate = stayDate,
            rooms = rooms.map { room ->
                RoomOffer(room, free[room.id] ?: 0, priceByRoom?.get(room.id)?.let { Money(it.amount, it.currency) })
            },
            pricesAvailable = priceByRoom != null,
        )
    }

    private fun validateStayDate(stayDate: LocalDate) {
        val today = LocalDate.now(clock)
        if (stayDate.isBefore(today) || stayDate.isAfter(today.plusDays(bookingWindowDays))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "Choose a night between today and $bookingWindowDays days from now")
        }
    }
}
