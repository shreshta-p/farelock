package com.farelock.reservation.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

data class RegisterRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String?,
    // BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated.
    @field:NotBlank @field:Size(min = 8, max = 72) val password: String?,
    @field:NotBlank @field:Size(max = 100) val fullName: String?,
)

data class LoginRequest(
    @field:NotBlank val email: String?,
    @field:NotBlank val password: String?,
)

@RestController
@RequestMapping("/api")
class AuthController(
    private val auth: AuthService,
    @Value("\${farelock.session-cookie.secure:false}") private val secureCookie: Boolean,
) {

    @PostMapping("/auth/register")
    fun register(@Valid @RequestBody req: RegisterRequest): ResponseEntity<User> {
        val result = auth.register(req.email!!, req.password!!, req.fullName!!)
        return withSessionCookie(HttpStatus.CREATED, result)
    }

    @PostMapping("/auth/login")
    fun login(@Valid @RequestBody req: LoginRequest): ResponseEntity<User> =
        withSessionCookie(HttpStatus.OK, auth.login(req.email!!, req.password!!))

    @PostMapping("/auth/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        sessionToken(request)?.let { auth.logout(it) }
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
            .build()
    }

    /** The interceptor has already checked the session and stored the user on the request. */
    @GetMapping("/me")
    fun me(@RequestAttribute(CURRENT_USER) user: User): User = user

    private fun withSessionCookie(status: HttpStatus, result: LoginResult) =
        ResponseEntity.status(status)
            .header(HttpHeaders.SET_COOKIE, cookie(result.sessionToken, auth.sessionLifetime).toString())
            .body(result.user)

    // HttpOnly: JavaScript cannot read it. SameSite=Lax: not sent on cross-site POSTs (CSRF protection).
    private fun cookie(value: String, maxAge: Duration) = ResponseCookie.from(SESSION_COOKIE, value)
        .httpOnly(true)
        .secure(secureCookie)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
}
