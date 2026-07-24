package me.gpipi

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises configureSecurity() on its own — no auth routes, no AuthService, no DB.
 * Replacing the config with a bare MapApplicationConfig means application.conf's module
 * chain (incl. configureDatabase) never loads, so this needs no Testcontainers Postgres.
 * Two throwaway routes stand in for the real ones: one sets a session, one lives behind
 * the "auth-session" guard.
 */
class SecurityTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-24T03:00:00Z"), ZoneOffset.UTC)

    private fun ApplicationTestBuilder.boot() {
        environment { config = MapApplicationConfig("session.signKey" to "test-sign-key") }
        application {
            configureSecurity(clock)
            routing {
                post("/test/login") {
                    call.sessions.set(UserSession("u1", clock.instant().epochSecond))
                    call.respond(HttpStatusCode.OK)
                }
                post("/test/login-expired") {
                    call.sessions.set(
                        UserSession(
                            userId = "u1",
                            issuedAt = clock.instant().minusSeconds(24L * 60 * 60).epochSecond,
                        ),
                    )
                    call.respond(HttpStatusCode.OK)
                }
                post("/test/login-near-expiry") {
                    call.sessions.set(
                        UserSession(
                            userId = "u1",
                            issuedAt = clock.instant().minusSeconds((24L * 60 * 60) - 1).epochSecond,
                        ),
                    )
                    call.respond(HttpStatusCode.OK)
                }
                authenticate("auth-session") {
                    get("/test/me") {
                        call.respondText(call.principal<UserSession>()!!.userId)
                    }
                }
            }
        }
    }

    @Test
    fun `guard rejects a request with no session`() = testApplication {
        boot()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/test/me").status)
    }

    @Test
    fun `session round-trips through the signed cookie`() = testApplication {
        boot()
        val c = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, c.post("/test/login").status)
        val res = c.get("/test/me")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("u1", res.bodyAsText())
    }

    @Test
    fun `cookie is HttpOnly and SameSite Lax`() = testApplication {
        boot()
        val setCookie = client.post("/test/login").headers[HttpHeaders.SetCookie] ?: ""
        assertTrue(setCookie.contains("HttpOnly", ignoreCase = true), "expected HttpOnly in: $setCookie")
        assertTrue(setCookie.contains("SameSite=Lax", ignoreCase = true), "expected SameSite=Lax in: $setCookie")
    }

    @Test
    fun `session cookie has an eight hour idle timeout`() = testApplication {
        boot()
        val setCookie = client.post("/test/login").headers[HttpHeaders.SetCookie] ?: ""
        assertTrue(setCookie.contains("Max-Age=28800"), "expected eight-hour Max-Age in: $setCookie")
    }

    @Test
    fun `authenticated activity renews the eight hour idle window`() = testApplication {
        boot()
        val c = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, c.post("/test/login").status)

        val setCookie = c.get("/test/me").headers[HttpHeaders.SetCookie] ?: ""
        assertTrue(setCookie.contains("Max-Age=28800"), "expected renewed eight-hour Max-Age in: $setCookie")
    }

    @Test
    fun `session is rejected at its twenty four hour absolute lifetime`() = testApplication {
        boot()
        val c = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, c.post("/test/login-expired").status)

        assertEquals(HttpStatusCode.Unauthorized, c.get("/test/me").status)
    }

    @Test
    fun `session remains valid immediately before its absolute lifetime`() = testApplication {
        boot()
        val c = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, c.post("/test/login-near-expiry").status)

        assertEquals(HttpStatusCode.OK, c.get("/test/me").status)
    }

    @Test
    fun `a tampered cookie is rejected`() = testApplication {
        boot()
        val res = client.get("/test/me") { header(HttpHeaders.Cookie, "session=not-a-valid-signed-value") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
