package me.gpipi

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.post
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.delay

class HttpClientsTest {
    @Test
    fun `OpenRouter uses a ninety second timeout by default`() {
        assertEquals(90_000L, OPENROUTER_REQUEST_TIMEOUT_MILLIS)
    }

    @Test
    fun `OpenRouter timeout does not retry the generation`() = testApplication {
        val attempts = AtomicInteger()
        application {
            routing {
                post("/completion") {
                    attempts.incrementAndGet()
                    delay(1_000)
                    call.respondText("ok")
                }
            }
        }
        val openRouterHttpClient = createClient {
            configureOpenRouterHttpClient(timeoutMillis = 200)
        }

        assertFailsWith<HttpRequestTimeoutException> {
            openRouterHttpClient.post("/completion")
        }
        assertEquals(1, attempts.get())
    }
}
