package me.gpipi.shopping

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import me.gpipi.support.configureWithTestDb

class ShoppingRoutesGuardTest {
    private fun ApplicationTestBuilder.boot() = configureWithTestDb()

    @Test
    fun `shopping list without a session is rejected`() = testApplication {
        boot()

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/shopping/items").status,
        )
    }

    @Test
    fun `shopping mutations without a session are rejected`() = testApplication {
        boot()
        val id = UUID.randomUUID()

        val response = client.put("/api/shopping/items/$id/remove") {
            header(HttpHeaders.Origin, "https://budget.test")
            contentType(ContentType.Application.Json)
            setBody("""{"currentMutationId":"${UUID.randomUUID()}"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
