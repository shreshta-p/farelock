package com.farelock.reservation.auth

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

data class User(val id: Long, val email: String, val fullName: String)

data class UserWithPassword(val user: User, val passwordHash: String)

@Repository
class AuthRepository(private val jdbc: JdbcClient) {

    /** Returns null when the email is already registered (unique constraint). */
    fun createUser(email: String, passwordHash: String, fullName: String): User? = try {
        val id = jdbc.sql("INSERT INTO users (email, password_hash, full_name) VALUES (?, ?, ?) RETURNING id")
            .params(email, passwordHash, fullName)
            .query(Long::class.java)
            .single()
        User(id, email, fullName)
    } catch (e: DuplicateKeyException) {
        null
    }

    fun findByEmail(email: String): UserWithPassword? =
        jdbc.sql("SELECT id, email, full_name, password_hash FROM users WHERE email = ?")
            .param(email)
            .query { rs, _ ->
                UserWithPassword(
                    User(rs.getLong("id"), rs.getString("email"), rs.getString("full_name")),
                    rs.getString("password_hash"),
                )
            }
            .optional().orElse(null)

    fun createSession(tokenHash: String, userId: Long, expiresAt: Instant) {
        jdbc.sql("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
            .params(tokenHash, userId, Timestamp.from(expiresAt))
            .update()
    }

    fun findUserBySession(tokenHash: String, now: Instant): User? =
        jdbc.sql(
            """
            SELECT u.id, u.email, u.full_name FROM sessions s JOIN users u ON u.id = s.user_id
            WHERE s.token_hash = ? AND s.expires_at > ?
            """
        )
            .params(tokenHash, Timestamp.from(now))
            .query { rs, _ -> User(rs.getLong("id"), rs.getString("email"), rs.getString("full_name")) }
            .optional().orElse(null)

    fun deleteSession(tokenHash: String) {
        jdbc.sql("DELETE FROM sessions WHERE token_hash = ?").param(tokenHash).update()
    }
}
