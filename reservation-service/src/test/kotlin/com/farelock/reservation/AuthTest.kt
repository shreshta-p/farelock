package com.farelock.reservation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTest : IntegrationTest() {

    @Test
    fun `register logs the user in with an HttpOnly session cookie`() {
        val c = client()
        val res = c.post("/api/auth/register", """{"email":"Ada@Example.test","password":"correct horse","fullName":"Ada L"}""")

        assertEquals(201, res.status)
        assertEquals("ada@example.test", res.body["email"].asString())
        val setCookie = res.headers.getFirst("Set-Cookie")!!
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Lax"))
        assertEquals("ada@example.test", c.get("/api/me").body["email"].asString())
    }

    @Test
    fun `passwords are stored as bcrypt hashes, never plaintext`() {
        client().post("/api/auth/register", """{"email":"b@example.test","password":"correct horse","fullName":"B"}""")
        val stored = jdbc.sql("SELECT password_hash FROM users WHERE email = 'b@example.test'").query(String::class.java).single()
        assertNotEquals("correct horse", stored)
        assertTrue(stored.startsWith("\$2a\$"))
    }

    @Test
    fun `duplicate email and weak input are rejected`() {
        val body = """{"email":"dup@example.test","password":"correct horse","fullName":"D"}"""
        client().post("/api/auth/register", body)
        assertEquals("EMAIL_TAKEN", client().post("/api/auth/register", body).code)
        assertEquals("VALIDATION_FAILED", client().post("/api/auth/register", """{"email":"nope","password":"short","fullName":""}""").code)
    }

    @Test
    fun `login checks the password and logout ends the session`() {
        client().post("/api/auth/register", """{"email":"c@example.test","password":"correct horse","fullName":"C"}""")

        assertEquals("INVALID_CREDENTIALS", client().post("/api/auth/login", """{"email":"c@example.test","password":"wrong pass"}""").code)
        assertEquals("INVALID_CREDENTIALS", client().post("/api/auth/login", """{"email":"nobody@example.test","password":"wrong pass"}""").code)

        val c = client()
        assertEquals(200, c.post("/api/auth/login", """{"email":"C@example.test","password":"correct horse"}""").status)
        assertEquals(200, c.get("/api/me").status)
        val oldCookie = c.sessionCookie

        c.post("/api/auth/logout")
        assertEquals("", c.sessionCookie!!.substringAfter("="), "cookie should be cleared")
        // The old token is invalid on the server too, not just cleared in the browser.
        c.sessionCookie = oldCookie
        assertEquals(204, c.get("/api/me").status)
    }

    @Test
    fun `protected endpoints require a session`() {
        assertEquals(204, client().get("/api/me").status) // "nobody", not an error
        assertEquals("UNAUTHENTICATED", client().get("/api/reservations").code)
        assertEquals(401, client().get("/api/reservations").status)
    }
}
