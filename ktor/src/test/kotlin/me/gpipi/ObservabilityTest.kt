package me.gpipi

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ObservabilityTest {

    @Test
    fun `requests receive a generated request id and preserve a valid incoming id`() = testApplication {
        application {
            configureObservability()
            routing {
                get("/probe") {
                    call.respondText("ok")
                }
            }
        }

        val generatedResponse = client.get("/probe")
        val generatedRequestId = assertNotNull(generatedResponse.headers[HttpHeaders.XRequestId])
        UUID.fromString(generatedRequestId)

        val suppliedRequestId = "diagnostic-request-123"
        val suppliedResponse = client.get("/probe") {
            header(HttpHeaders.XRequestId, suppliedRequestId)
        }

        assertEquals(suppliedRequestId, suppliedResponse.headers[HttpHeaders.XRequestId])
    }

    @Test
    fun `unhandled errors return a safe response with the request id`() = testApplication {
        application {
            configureObservability()
            configureStatusPages()
            routing {
                get("/boom") {
                    error("sensitive diagnostic detail")
                }
            }
        }

        val requestId = "failing-request-123"
        val response = client.get("/boom") {
            header(HttpHeaders.XRequestId, requestId)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(requestId, response.headers[HttpHeaders.XRequestId])
        assertFalse(response.bodyAsText().contains("sensitive diagnostic detail"))
    }
}
