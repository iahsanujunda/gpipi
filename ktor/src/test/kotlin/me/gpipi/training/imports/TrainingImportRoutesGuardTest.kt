package me.gpipi.training.imports

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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

class TrainingImportRoutesGuardTest {
    private fun ApplicationTestBuilder.boot() = configureWithTestDb()

    @Test
    fun `import reads without a session are rejected`() = testApplication {
        boot()
        val id = UUID.randomUUID()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/training/google/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/training/google/sheets").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/training/imports/$id").status)
    }

    @Test
    fun `import mutations without a session are rejected`() = testApplication {
        boot()
        val id = UUID.randomUUID()

        val start = client.post("/api/training/programs/$id/imports") {
            header(HttpHeaders.Origin, "https://budget.test")
            contentType(ContentType.Application.Json)
            setBody("""{"selectionToken":"opaque-selection"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, start.status)

        val mapping = client.put("/api/training/imports/$id/mapping") {
            header(HttpHeaders.Origin, "https://budget.test")
            contentType(ContentType.Application.Json)
            setBody("""{"tabs":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, mapping.status)

        val apply = client.post("/api/training/imports/$id/apply") {
            header(HttpHeaders.Origin, "https://budget.test")
        }
        assertEquals(HttpStatusCode.Unauthorized, apply.status)
    }
}
