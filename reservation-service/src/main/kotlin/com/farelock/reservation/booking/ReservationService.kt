package com.farelock.reservation.booking

import com.farelock.reservation.auth.User
import com.farelock.reservation.catalog.CatalogRepository
import com.farelock.reservation.catalog.PricingClient
import com.farelock.reservation.catalog.Quote
import com.farelock.reservation.common.ApiError
import com.farelock.reservation.common.ApiException
import com.farelock.reservation.common.io
import com.farelock.reservation.common.notFound
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CreateReservationRequest(
    val quoteId: UUID,
    val roomTypeId: Long,
    val stayDate: LocalDate,
    val guestName: String,
    val cancellationProtection: Boolean,
)

@Service
class ReservationService(
    private val reservations: ReservationRepository,
    private val inventory: InventoryRepository,
    private val catalog: CatalogRepository,
    private val idempotency: IdempotencyStore,
    private val pricing: PricingClient,
    private val tx: TransactionTemplate,
    private val json: JsonMapper,
    private val clock: Clock,
    private val meters: MeterRegistry,
    @Value("\${farelock.hold-duration:10m}") private val holdDuration: Duration,
) {

    /**
     * Creates a reservation that holds one room for [holdDuration].
     *
     * 1. Claim the Idempotency-Key (short insert, auto-committed).
     * 2. Fetch the quote from Pricing, with no transaction open.
     * 3. One transaction: take a room from inventory, insert the reservation, store the response.
     */
    suspend fun create(user: User, idempotencyKey: String, request: CreateReservationRequest): StoredResponse {
        val requestHash = sha256("POST /api/reservations " + json.writeValueAsString(request))

        when (val claim = io { idempotency.claim(user.id, idempotencyKey, requestHash) }) {
            is Claim.Replay -> return claim.response
            Claim.Conflict -> throw ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used for a different request")
            Claim.InProgress -> throw ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                "This request is already being processed, retry shortly")
            Claim.Acquired -> {}
        }

        try {
            val quote = pricing.quote(request.quoteId)
            checkQuoteMatches(quote, request)
            return io { holdRoom(user, idempotencyKey, request, quote) }
                .also { count("created") }
        } catch (e: ApiException) {
            if (e.status.is4xxClientError) {
                // A definite answer ("sold out", "quote expired"): remember it so retries get the same one.
                val response = StoredResponse(e.status.value(), json.writeValueAsString(ApiError(e.code, e.message ?: e.code)))
                io { idempotency.complete(user.id, idempotencyKey, response) }
                count(e.code.lowercase())
                return response
            }
            io { idempotency.release(user.id, idempotencyKey) } // temporary failure: allow a retry
            throw e
        } catch (e: Exception) {
            io { idempotency.release(user.id, idempotencyKey) }
            throw e
        }
    }

    fun listFor(user: User): List<Reservation> = reservations.findForUser(user.id)

    /** Returns 404 (not 403) for other users' reservations so ids cannot be probed. */
    fun getFor(user: User, id: UUID): Reservation =
        reservations.find(id)?.takeIf { it.userId == user.id } ?: throw notFound("Reservation")

    private fun holdRoom(user: User, key: String, request: CreateReservationRequest, quote: Quote): StoredResponse =
        tx.execute {
            val room = catalog.findRoomType(request.roomTypeId) ?: throw notFound("Room type")
            val hotel = catalog.findHotel(room.hotelId)!!
            val cancelDeadline = request.stayDate.atStartOfDay(ZoneId.of(hotel.timezone)).toInstant()
            if (request.cancellationProtection && !clock.instant().isBefore(cancelDeadline)) {
                // Check-in day has already started at the hotel: protection could never be used.
                throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PROTECTION_UNAVAILABLE",
                    "Free cancellation can't be added once check-in day has started at the hotel")
            }
            val id = UUID.randomUUID()
            reservations.insert(
                NewReservation(
                    id = id,
                    userId = user.id,
                    hotelId = room.hotelId,
                    roomTypeId = room.id,
                    stayDate = request.stayDate,
                    guestName = request.guestName.trim(),
                    quoteId = quote.id,
                    amount = quote.amount,
                    protectionFee = if (request.cancellationProtection) quote.protectionFee else null,
                    currency = quote.currency,
                    holdExpiresAt = clock.instant().plus(holdDuration),
                    // The policy is frozen at booking time, like the price: cancellation closes when
                    // the stay date begins in the hotel's own time zone (midnight in Tokyo for a Kyoto hotel).
                    cancelDeadline = cancelDeadline,
                )
            )
            val response = StoredResponse(HttpStatus.CREATED.value(), json.writeValueAsString(reservations.find(id)))
            idempotency.complete(user.id, key, response) // same transaction: both commit or neither does

            // Take the room LAST. Every booking for this room and night updates the same inventory row,
            // and the row stays locked until commit, so the lock is held for one statement only.
            // (Measured: taking it first made bookings queue behind each other's later statements;
            // see docs/benchmark.md.) If sold out, the exception rolls back the insert above.
            if (!inventory.tryHold(room.id, request.stayDate)) {
                throw ApiException(HttpStatus.CONFLICT, "SOLD_OUT", "No rooms of this type are left for that night")
            }
            response
        }!!

    private fun checkQuoteMatches(quote: Quote, request: CreateReservationRequest) {
        if (quote.roomTypeId != request.roomTypeId || quote.stayDate != request.stayDate) {
            throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "QUOTE_MISMATCH", "The quote is for a different room or night")
        }
        if (quote.expired || !clock.instant().isBefore(quote.expiresAt)) {
            throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "QUOTE_EXPIRED", "This price has expired, please get a new quote")
        }
    }

    private fun count(outcome: String) = meters.counter("farelock.reservations", "outcome", outcome).increment()

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
