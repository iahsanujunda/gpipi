package me.gpipi.category

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.UserSession
import me.gpipi.configureSecurity
import me.gpipi.configureSerialization

class BudgetRoutesTest {
    private val service = mockk<BudgetService>()

    private fun ApplicationTestBuilder.boot(
        clock: Clock = Clock.systemUTC(),
    ) {
        application {
            configureSerialization()
            routing {
                budgetApiRoutes(service, clock)
            }
        }
    }

    private fun ApplicationTestBuilder.apiClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    private fun ApplicationTestBuilder.bootAuthenticated(clock: Clock) {
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
                    budgetApiRoutes(service, clock)
                }
            }
        }
    }

    private fun ApplicationTestBuilder.authenticatedApiClient() = createClient {
        install(HttpCookies)
        install(ContentNegotiation) { json() }
    }

    private fun request() = UpsertBudgetRequest(
        name = "Monthly Groceries",
        description = "Supermarket and pantry spending",
        period = "MONTHLY",
        amount = 75_000L,
        active = true,
        slackLoggable = true,
        accountId = UUID.randomUUID().toString(),
    )

    @Test
    fun `GET budgets returns the active budget lines on the exact frontend path`() = testApplication {
        val budgets = listOf(
            BudgetRow(
                id = "00000000-0000-0000-0000-000000000001",
                name = "Monthly Groceries",
                description = "Supermarket and pantry spending",
                period = "MONTHLY",
                amount = 75_000L,
                active = true,
                slackLoggable = true,
                accountId = "00000000-0000-0000-0000-000000000010",
                accountName = "Default wallet",
            ),
        )
        coEvery { service.listBudgets() } returns budgets
        boot()

        val response = apiClient().get("/api/budgets")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(budgets, response.body())
        coVerify(exactly = 1) { service.listBudgets() }
    }

    @Test
    fun `POST category returns created id`() = testApplication {
        val request = request()
        val id = UUID.randomUUID()
        coEvery { service.create(request) } returns BudgetMutationResult.Created(id)
        boot()

        val response = apiClient().post("/api/budgets/categories") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(id.toString(), response.body<JsonObject>()["id"]?.jsonPrimitive?.content)
        coVerify(exactly = 1) { service.create(request) }
    }

    @Test
    fun `POST category returns bad request for an invalid budget`() = testApplication {
        val request = request()
        coEvery { service.create(request) } returns
            BudgetMutationResult.Invalid("'name' must not be blank.")
        boot()

        val response = apiClient().post("/api/budgets/categories") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "'name' must not be blank.",
            response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `POST category returns conflict for a duplicate name`() = testApplication {
        val request = request()
        coEvery { service.create(request) } returns
            BudgetMutationResult.DuplicateName(request.name)
        boot()

        val response = apiClient().post("/api/budgets/categories") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(
            "A budget line named 'Monthly Groceries' already exists.",
            response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `PUT category rejects a malformed id without calling the service`() = testApplication {
        boot()

        val response = apiClient().put("/api/budgets/categories/not-a-uuid") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "'id' must be a UUID.",
            response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
        )
        coVerify(exactly = 0) { service.update(any(), any()) }
    }

    @Test
    fun `PUT category returns no content after an update`() = testApplication {
        val id = UUID.randomUUID()
        val request = request()
        coEvery { service.update(id, request) } returns BudgetMutationResult.Updated
        boot()

        val response = apiClient().put("/api/budgets/categories/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { service.update(id, request) }
    }

    @Test
    fun `PUT category returns not found for an unknown budget`() = testApplication {
        val id = UUID.randomUUID()
        val request = request()
        coEvery { service.update(id, request) } returns BudgetMutationResult.NotFound
        boot()

        val response = apiClient().put("/api/budgets/categories/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT category returns conflict for a duplicate name`() = testApplication {
        val id = UUID.randomUUID()
        val request = request()
        coEvery { service.update(id, request) } returns
            BudgetMutationResult.DuplicateName(request.name)
        boot()

        val response = apiClient().put("/api/budgets/categories/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(
            "A budget line named 'Monthly Groceries' already exists.",
            response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `PUT deactivate returns no content after deactivation`() = testApplication {
        val id = UUID.randomUUID()
        coEvery { service.deactivate(id) } returns BudgetMutationResult.Updated
        boot()

        val response = apiClient().put("/api/budgets/categories/$id/deactivate")

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { service.deactivate(id) }
    }

    @Test
    fun `PUT deactivate rejects a malformed id without calling the service`() = testApplication {
        boot()

        val response = apiClient().put("/api/budgets/categories/not-a-uuid/deactivate")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "'id' must be a UUID.",
            response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
        )
        coVerify(exactly = 0) { service.deactivate(any()) }
    }

    @Test
    fun `PUT deactivate returns not found for an unknown budget`() = testApplication {
        val id = UUID.randomUUID()
        coEvery { service.deactivate(id) } returns BudgetMutationResult.NotFound
        boot()

        val response = apiClient().put("/api/budgets/categories/$id/deactivate")

        assertEquals(HttpStatusCode.NotFound, response.status)
        coVerify(exactly = 1) { service.deactivate(id) }
    }

    @Test
    fun `GET spend returns spend versus cap for the requested date`() = testApplication {
        val date = LocalDate.of(2026, 7, 24)
        val rows = listOf(
            SpendRow(
                categoryId = "00000000-0000-0000-0000-000000000001",
                name = "Monthly Groceries",
                period = "MONTHLY",
                windowStart = "2026-07-01",
                windowEndExclusive = "2026-08-01",
                baseCap = 75_000L,
                appliedCarry = 0L,
                effectiveAllowance = 75_000L,
                spent = 20_000L,
                remaining = 55_000L,
            ),
        )
        coEvery { service.spendVsCap(date) } returns rows
        boot()

        val response = apiClient().get("/api/budgets/spend?date=2026-07-24")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(rows, response.body<List<SpendRow>>())
        coVerify(exactly = 1) { service.spendVsCap(date) }
    }

    @Test
    fun `GET spend defaults to the current date in Tokyo`() = testApplication {
        val clock = Clock.fixed(
            Instant.parse("2026-07-23T15:30:00Z"),
            ZoneOffset.UTC,
        )
        val tokyoDate = LocalDate.of(2026, 7, 24)
        coEvery { service.spendVsCap(tokyoDate) } returns emptyList()
        boot(clock)

        val response = apiClient().get("/api/budgets/spend")

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { service.spendVsCap(tokyoDate) }
    }

    @Test
    fun `GET spend rejects a malformed date without calling the service`() = testApplication {
        boot()

        val response = apiClient().get("/api/budgets/spend?date=not-a-date")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "'date' must be an ISO-8601 date (YYYY-MM-DD).",
            response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
        )
        coVerify(exactly = 0) { service.spendVsCap(any()) }
    }

    @Test
    fun `POST carry-forward uses the authenticated actor and maps a new application`() =
        testApplication {
            val clock = Clock.fixed(Instant.parse("2026-07-20T03:00:00Z"), ZoneOffset.UTC)
            val categoryId = UUID.randomUUID()
            val request = ApplyCarryForwardRequest("2026-07-20", 3_000L)
            val write = CarryForwardWrite(
                categoryId = categoryId.toString(),
                targetWindowStart = "2026-07-20",
                amount = 3_000L,
                effectiveAllowance = 18_000L,
                replayed = false,
            )
            coEvery {
                service.applyCarryForward(categoryId, "2026-07-20", 3_000L, "U-web")
            } returns CarryForwardResult.Applied(write)
            bootAuthenticated(clock)
            val client = authenticatedApiClient()
            client.post("/test/login")

            val response = client.post("/api/budgets/categories/$categoryId/carry-forward") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.body<JsonObject>()
            assertEquals("3000", body["amount"]?.jsonPrimitive?.content)
            assertEquals(
                "18000",
                body["effectiveAllowance"]?.jsonPrimitive?.content,
            )
            coVerify(exactly = 1) {
                service.applyCarryForward(categoryId, "2026-07-20", 3_000L, "U-web")
            }
        }
}
