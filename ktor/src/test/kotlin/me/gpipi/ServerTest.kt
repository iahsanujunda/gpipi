package me.gpipi

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `health endpoint returns 200`() = testApplication {
        // loads application.conf → wires configureRouting (health + slack)
        configure()
        // liveness must answer without a Slack signature
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

}
