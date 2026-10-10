package com.farelock.reservation.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Every error response has the same small shape: a stable code and a human message. */
data class ApiError(val code: String, val message: String)

/** Thrown anywhere in the service to return a specific HTTP status and error code. */
class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

fun notFound(what: String) = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what not found")

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = error(e.status, e.code, e.message ?: e.code)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException) = error(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
        e.bindingResult.fieldErrors.joinToString("; ") { "${it.field} ${it.defaultMessage}" },
    )

    @ExceptionHandler(MissingRequestHeaderException::class, MissingServletRequestParameterException::class)
    fun missing(e: Exception) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.message ?: "Missing input")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, HttpMessageNotReadableException::class)
    fun unreadable(e: Exception) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(e: Exception) =
        error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send JSON with Content-Type: application/json")

    @ExceptionHandler(NoResourceFoundException::class)
    fun noRoute(e: Exception) = error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled error", e)
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong")
    }

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ApiError(code, message))
}
