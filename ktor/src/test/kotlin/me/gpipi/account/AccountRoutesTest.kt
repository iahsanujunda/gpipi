package me.gpipi.account

import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.UserSession
import me.gpipi.configureSecurity
import me.gpipi.configureSerialization

class AccountRoutesTest {
    private val service = mockk<AccountService>()
    private val clock = Clock.fixed(
        Instant.parse("2026-07-29T03:45:00Z"),
        ZoneOffset.UTC,
    )

    private fun ApplicationTestBuilder.boot() {
        environment {
            config = MapApplicationConfig("session.signKey" to "test-session-key")
        }
        application {
            configureSecurity(clock)
            configureSerialization()
            routing {
                post("/test/login") {
                    call.sessions.set(UserSession("U-web", clock.instant().epochSecond))
                    call.respond(HttpStatusCode.OK)
                }
                authenticate("auth-session") {
                    accountApiRoutes(service)
                }
            }
        }
    }

    private fun ApplicationTestBuilder.apiClient() = createClient {
        install(HttpCookies)
        install(ContentNegotiation) { json() }
    }

    @Test
    fun `GET accounts exposes derived balances and budget counts`() = testApplication {
        val id = UUID.randomUUID()
        coEvery { service.listAccounts() } returns listOf(
            AccountRecord(id, "Everyday", "Daily spending", -5_000, 3),
        )
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.get("/api/accounts")
        val account = response.body<JsonArray>().single() as JsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(id.toString(), account["id"]?.jsonPrimitive?.content)
        assertEquals("-5000", account["balance"]?.jsonPrimitive?.content)
        assertEquals("3", account["assignedBudgetCount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST preview maps shared validation failure to bad request`() = testApplication {
        val request = MovementInputRequest(
            toAccountId = UUID.randomUUID().toString(),
            amount = 1_000,
            occurredOn = "2026-07-30",
        )
        coEvery { service.preview(any()) } returns
            MovementResult.Invalid("Future money movements are not allowed.")
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.post("/api/money-movements/preview") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "Future money movements are not allowed.",
            response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `POST movement uses authenticated actor and distinguishes create from replay`() =
        testApplication {
            val accountId = UUID.randomUUID()
            val key = UUID.randomUUID()
            val request = CreateMovementRequest(
                idempotencyKey = key.toString(),
                toAccountId = accountId.toString(),
                amount = 20_000,
                occurredOn = "2026-07-29",
                note = "July salary",
            )
            val movement = MovementRecord(
                id = UUID.randomUUID(),
                idempotencyKey = key,
                fromAccountId = null,
                toAccountId = accountId,
                amount = 20_000,
                occurredAt = OffsetDateTime.parse("2026-07-29T03:45:00Z"),
                note = "July salary",
                createdByUserId = "U-web",
                createdAt = OffsetDateTime.parse("2026-07-29T03:45:00Z"),
            )
            val projection = BalanceProjectionRecord(
                accountId,
                "Everyday",
                0,
                20_000,
                20_000,
            )
            val first = MovementResult.Recorded(
                MovementWriteRecord(
                    movement,
                    OffsetDateTime.parse("2026-07-29T03:45:00Z"),
                    listOf(projection),
                    replayed = false,
                ),
            )
            val replay = MovementResult.Recorded(first.write.copy(replayed = true))
            coEvery { service.record(key.toString(), any(), "U-web") } returnsMany
                listOf(first, replay)
            boot()
            val client = apiClient()
            client.post("/test/login")

            val created = client.post("/api/money-movements") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val replayed = client.post("/api/money-movements") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(HttpStatusCode.OK, replayed.status)
            assertEquals(
                movement.id.toString(),
                created.body<JsonObject>()["movement"]
                    ?.let { it as JsonObject }
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.content,
            )
            coVerify(exactly = 2) { service.record(key.toString(), any(), "U-web") }
        }
}
