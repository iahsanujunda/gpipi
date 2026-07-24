package me.gpipi.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import me.gpipi.configureSecurity
import me.gpipi.configureSerialization

class AuthRoutesTest {
    private val auth = mockk<AuthService>()
    private val clock = Clock.fixed(Instant.parse("2026-07-24T03:00:00Z"), ZoneOffset.UTC)

    private fun ApplicationTestBuilder.boot() {
        environment {
            config = MapApplicationConfig(
                "session.signKey" to "test-session-sign-key",
                "app.env" to "DEV",
            )
        }
        application {
            configureSerialization()
            configureSecurity()
            routing {
                authRoutes(auth, clock)
            }
        }
    }

    private fun ApplicationTestBuilder.authClient(): HttpClient = createClient {
        install(ContentNegotiation) { json() }
        install(HttpCookies)
    }

    private suspend fun HttpClient.redeem(nonce: String) = post("/api/auth/redeem") {
        contentType(ContentType.Application.Json)
        setBody(RedeemRequest(nonce))
    }

    @Test
    fun `redeem with a blank nonce returns bad request without calling the service`() = testApplication {
        boot()

        val response = authClient().redeem(" ")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) { auth.redeem(any()) }
    }

    @Test
    fun `redeem with a nonce longer than 256 characters returns bad request without calling the service`() =
        testApplication {
            boot()

            val response = authClient().redeem("a".repeat(257))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            coVerify(exactly = 0) { auth.redeem(any()) }
        }

    @Test
    fun `redeem with an invalid nonce returns unauthorized`() = testApplication {
        coEvery { auth.redeem("invalid-nonce") } returns null
        boot()

        val response = authClient().redeem("invalid-nonce")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        coVerify(exactly = 1) { auth.redeem("invalid-nonce") }
    }

    @Test
    fun `redeem with a valid nonce returns the session and sets its cookie`() = testApplication {
        coEvery { auth.redeem("valid-nonce") } returns "U1"
        boot()

        val response = authClient().redeem("valid-nonce")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(SessionResponse("U1"), response.body())
        assertNotNull(response.headers[HttpHeaders.SetCookie])
        coVerify(exactly = 1) { auth.redeem("valid-nonce") }
    }

    @Test
    fun `session without a cookie returns unauthorized`() = testApplication {
        boot()

        val response = client.get("/api/auth/session")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `session after redeem returns the authenticated user`() = testApplication {
        coEvery { auth.redeem("valid-nonce") } returns "U1"
        boot()
        val client = authClient()
        assertEquals(HttpStatusCode.OK, client.redeem("valid-nonce").status)

        val response = client.get("/api/auth/session")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(SessionResponse("U1"), response.body())
    }

    @Test
    fun `logout clears the session cookie`() = testApplication {
        coEvery { auth.redeem("valid-nonce") } returns "U1"
        boot()
        val client = authClient()
        assertEquals(HttpStatusCode.OK, client.redeem("valid-nonce").status)

        val logout = client.post("/api/auth/logout")
        val sessionAfterLogout = client.get("/api/auth/session")

        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, sessionAfterLogout.status)
    }
}
