package com.farelock.reservation.booking

import com.farelock.reservation.auth.CURRENT_USER
import com.farelock.reservation.auth.User
import com.farelock.reservation.common.ApiException
import com.farelock.reservation.payment.PaymentService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class CreateReservationBody(
    @field:NotNull val quoteId: UUID?,
    @field:NotNull @field:Positive val roomTypeId: Long?,
    @field:NotNull val stayDate: LocalDate?,
    @field:NotBlank @field:Size(max = 100) val guestName: String?,
)

data class PaymentBody(@field:NotBlank val paymentToken: String?)

@RestController
@RequestMapping("/api/reservations")
class ReservationController(
    private val reservations: ReservationService,
    private val payments: PaymentService,
) {

    @PostMapping
    suspend fun create(
        @RequestAttribute(CURRENT_USER) user: User,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody body: CreateReservationBody,
    ): ResponseEntity<String> {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 100) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 1-100 characters")
        }
        val request = CreateReservationRequest(body.quoteId!!, body.roomTypeId!!, body.stayDate!!, body.guestName!!)
        // The body is already-serialized JSON: either fresh, or the stored answer to an earlier identical request.
        val response = reservations.create(user, idempotencyKey, request)
        return ResponseEntity.status(response.status).contentType(MediaType.APPLICATION_JSON).body(response.body)
    }

    @PostMapping("/{id}/payment")
    suspend fun pay(
        @RequestAttribute(CURRENT_USER) user: User,
        @PathVariable id: UUID,
        @Valid @RequestBody body: PaymentBody,
    ): ResponseEntity<Reservation> {
        val result = payments.pay(user, id, body.paymentToken!!)
        return ResponseEntity.status(result.httpStatus).body(result.reservation)
    }

    @GetMapping
    fun list(@RequestAttribute(CURRENT_USER) user: User) = reservations.listFor(user)

    @GetMapping("/{id}")
    fun get(@RequestAttribute(CURRENT_USER) user: User, @PathVariable id: UUID) = reservations.getFor(user, id)
}
