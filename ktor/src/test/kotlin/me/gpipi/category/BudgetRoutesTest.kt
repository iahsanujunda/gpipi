package me.gpipi.category

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.configureSerialization

class BudgetRoutesTest {
    private val service = mockk<BudgetService>()

    private fun ApplicationTestBuilder.boot() {
        application {
            configureSerialization()
            routing {
                budgetApiRoutes(service)
            }
        }
    }

    private fun ApplicationTestBuilder.apiClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    private fun request() = UpsertBudgetRequest(
        name = "Monthly Groceries",
        description = "Supermarket and pantry spending",
        period = "MONTHLY",
        amount = 75_000L,
        active = true,
        slackLoggable = true,
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
                cap = 75_000L,
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
}
