package me.gpipi

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import me.gpipi.support.configureWithTestDb
import kotlin.test.*

class ServerTest {

    @Test
    fun `health endpoint returns 200`() = testApplication {
        // loads application.conf → wires configureRouting (health + slack) + configureDatabase.
        // Inject a dummy secret and point db.* at the test container so startup succeeds.
        configureWithTestDb()
        // liveness must answer without a Slack signature
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `readiness endpoint returns 200 when the db is reachable`() = testApplication {
        // db.* points at the test container, so the SELECT 1 succeeds.
        configureWithTestDb()
        assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
    }

}
