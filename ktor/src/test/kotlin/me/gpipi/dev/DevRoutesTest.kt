package me.gpipi.dev

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import me.gpipi.support.configureWithTestDb
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The dev-only extraction endpoint. Both tests stay offline: outside DEV the route isn't
 * registered (404), and the DEV case posts a blank body so the guard short-circuits with 400
 * before OpenRouter is ever called. The real extraction path is a manual/live check, not CI.
 */
class DevRoutesTest {

    @Test
    fun `dev extract route is absent when not DEV`() = testApplication {
        configureWithTestDb()   // app.env resolves to PROD from application.conf
        val res = client.post("/dev/extract") { setBody("1500 for ramen") }
        // Fail-safe gate: the route must never exist unless APP_ENV=DEV is set explicitly.
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `dev extract rejects a blank body when DEV`() = testApplication {
        configureWithTestDb(appEnv = "DEV")
        val res = client.post("/dev/extract") { setBody("") }
        // 400 proves the route IS registered under DEV and the blank guard runs ahead of extract().
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertContains(res.bodyAsText(), "empty body")
    }
}
