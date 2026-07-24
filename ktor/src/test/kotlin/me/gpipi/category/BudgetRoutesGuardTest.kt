package me.gpipi.category

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import me.gpipi.support.configureWithTestDb

class BudgetRoutesGuardTest {
    private val budgetBody =
        """
        {
          "name": "Monthly Groceries",
          "description": "Supermarket spending",
          "period": "MONTHLY",
          "amount": 75000
        }
        """.trimIndent()

    /** Boots the real application.conf module chain with the Testcontainers database. */
    private fun ApplicationTestBuilder.boot() = configureWithTestDb()

    @Test
    fun `budget list without a session is rejected with 401`() = testApplication {
        boot()

        val response = client.get("/api/budgets")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `budget creation without a session is rejected with 401`() = testApplication {
        boot()

        val response = client.post("/api/budgets/categories") {
            contentType(ContentType.Application.Json)
            setBody(budgetBody)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `budget update without a session is rejected with 401`() = testApplication {
        boot()
        val id = "00000000-0000-0000-0000-000000000001"

        val response = client.put("/api/budgets/categories/$id") {
            contentType(ContentType.Application.Json)
            setBody(budgetBody)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `budget deactivation without a session is rejected with 401`() = testApplication {
        boot()
        val id = "00000000-0000-0000-0000-000000000001"

        val response = client.put("/api/budgets/categories/$id/deactivate")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `budget spend without a session is rejected with 401`() = testApplication {
        boot()

        val response = client.get("/api/budgets/spend?date=2026-07-24")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
