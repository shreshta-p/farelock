package com.farelock.reservation.auth

import com.farelock.reservation.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

data class LoginResult(val user: User, val sessionToken: String)

@Service
class AuthService(private val repository: AuthRepository, private val clock: Clock) {
    private val encoder = BCryptPasswordEncoder()
    private val random = SecureRandom()

    // Used when the email does not exist, so a failed login takes the same time
    // either way and does not reveal which emails are registered.
    private val dummyHash = encoder.encode("not-a-real-password")

    val sessionLifetime: Duration = Duration.ofDays(7)

    fun register(email: String, password: String, fullName: String): LoginResult {
        val user = repository.createUser(normalize(email), encoder.encode(password)!!, fullName.trim())
            ?: throw ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists")
        return LoginResult(user, startSession(user.id))
    }

    fun login(email: String, password: String): LoginResult {
        val found = repository.findByEmail(normalize(email))
        val passwordOk = encoder.matches(password, found?.passwordHash ?: dummyHash)
        if (found == null || !passwordOk) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Incorrect email or password")
        }
        return LoginResult(found.user, startSession(found.user.id))
    }

    fun logout(token: String) = repository.deleteSession(hash(token))

    fun userForSession(token: String): User? = repository.findUserBySession(hash(token), clock.instant())

    private fun startSession(userId: Long): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        repository.createSession(hash(token), userId, clock.instant().plus(sessionLifetime))
        return token
    }

    private fun normalize(email: String) = email.trim().lowercase()

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
