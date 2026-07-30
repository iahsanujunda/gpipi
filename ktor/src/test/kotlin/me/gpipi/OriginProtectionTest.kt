package me.gpipi

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import me.gpipi.support.configureWithTestDb

class OriginProtectionTest {
    @Test
    fun `unsafe api request rejects a missing origin before route logic`() = testApplication {
        configureWithTestDb()

        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/auth/redeem").status,
        )
    }

    @Test
    fun `unsafe api request rejects an untrusted origin`() = testApplication {
        configureWithTestDb()

        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/money-movements/preview") {
                header(HttpHeaders.Origin, "https://attacker.example")
            }.status,
        )
    }

    @Test
    fun `trusted origin reaches the session guard`() = testApplication {
        configureWithTestDb()

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/money-movements/preview") {
                header(HttpHeaders.Origin, "https://budget.test")
            }.status,
        )
    }

    @Test
    fun `safe api reads do not require an origin`() = testApplication {
        configureWithTestDb()

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/accounts").status,
        )
    }
}
