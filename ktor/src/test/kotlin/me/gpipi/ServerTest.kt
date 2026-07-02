package me.gpipi

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `health endpoint returns 200`() = testApplication {
        // loads application.conf → wires configureRouting (health + slack).
        // Inject a dummy secret so the startup fail-fast check passes.
        configure {
            put("slack.signingSecret", "test-signing-secret")
        }
        // liveness must answer without a Slack signature
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

}
