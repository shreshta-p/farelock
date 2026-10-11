package com.farelock.pricing

import org.slf4j.LoggerFactory
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Every error response has the same small shape: a stable code and a human message. */
data class ApiError(val code: String, val message: String)

class NotFoundException(message: String) : RuntimeException(message)
class BadRequestException(message: String) : RuntimeException(message)

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class, NoResourceFoundException::class)
    fun notFound(e: Exception) = error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "Not found")

    @ExceptionHandler(BadRequestException::class, MissingServletRequestParameterException::class)
    fun badRequest(e: Exception) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.message ?: "Bad request")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, HttpMessageNotReadableException::class)
    fun unreadable(e: Exception) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException) = error(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
        e.bindingResult.fieldErrors.joinToString("; ") { "${it.field} ${it.defaultMessage}" },
    )

    // The database is unreachable (Hikari gave up after its 2s timeout): temporary, so 503 tells clients to retry.
    @ExceptionHandler(CannotGetJdbcConnectionException::class, CannotCreateTransactionException::class)
    fun databaseDown(e: Exception): ResponseEntity<ApiError> {
        log.error("Database unavailable: {}", e.message)
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Temporarily unavailable, please retry")
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled error", e)
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong")
    }

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ApiError(code, message))
}
