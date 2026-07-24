package me.gpipi.slack

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.gpipi.configureSerialization

class SlackClientTest {
    @Test
    fun `postEphemeral posts the channel user and text to the ephemeral endpoint`() = testApplication {
        lateinit var receivedBody: JsonObject
        var authorization: String? = null

        application {
            configureSerialization()
            routing {
                post("/api/chat.postEphemeral") {
                    authorization = call.request.headers[HttpHeaders.Authorization]
                    receivedBody = call.receive()
                    call.respond(buildJsonObject { put("ok", true) })
                }
            }
        }

        val http = createClient {
            install(ContentNegotiation) { json() }
        }
        val slack = SlackClient(
            http = http,
            botToken = "xoxb-test",
            apiBaseUrl = "/api",
        )

        slack.postEphemeral(
            channel = "C1",
            user = "U1",
            text = "Open your budget",
        )

        assertEquals("Bearer xoxb-test", authorization)
        assertEquals(setOf("channel", "user", "text"), receivedBody.keys)
        assertEquals("C1", receivedBody.getValue("channel").jsonPrimitive.content)
        assertEquals("U1", receivedBody.getValue("user").jsonPrimitive.content)
        assertEquals("Open your budget", receivedBody.getValue("text").jsonPrimitive.content)
    }
}
