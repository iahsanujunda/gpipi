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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import me.gpipi.configureSerialization

class SlackClientTest {
    @Test
    fun `replaceCard sends replacement blocks with fallback text`() = testApplication {
        lateinit var receivedBody: JsonObject
        application {
            configureSerialization()
            routing {
                post("/response") {
                    receivedBody = call.receive()
                    call.respond(buildJsonObject { put("ok", true) })
                }
            }
        }

        val slack = SlackClient(
            http = createClient {
                install(ContentNegotiation) { json() }
            },
            botToken = "xoxb-test",
            apiBaseUrl = "/api",
        )
        val blocks = buildJsonArray {
            addJsonObject { put("type", "section") }
        }

        slack.replaceCard(
            responseUrl = "/response",
            text = "Milk marked bought ✓",
            blocks = blocks,
        )

        assertEquals(true, receivedBody["replace_original"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Milk marked bought ✓", receivedBody["text"]!!.jsonPrimitive.content)
        assertEquals(blocks, receivedBody["blocks"]!!.jsonArray)
    }

    @Test
    fun `replaceCard without blocks preserves text-only behavior`() = testApplication {
        lateinit var receivedBody: JsonObject
        application {
            configureSerialization()
            routing {
                post("/response") {
                    receivedBody = call.receive()
                    call.respond(buildJsonObject { put("ok", true) })
                }
            }
        }

        val slack = SlackClient(
            http = createClient {
                install(ContentNegotiation) { json() }
            },
            botToken = "xoxb-test",
            apiBaseUrl = "/api",
        )

        slack.replaceCard("/response", "Recorded ✓")

        assertEquals(true, receivedBody["replace_original"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Recorded ✓", receivedBody["text"]!!.jsonPrimitive.content)
        assertTrue("blocks" !in receivedBody)
    }

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

    @Test
    fun `postEphemeralCard posts blocks with fallback text to the ephemeral endpoint`() = testApplication {
        lateinit var receivedBody: JsonObject

        application {
            configureSerialization()
            routing {
                post("/api/chat.postEphemeral") {
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
        val blocks = buildJsonArray {
            addJsonObject { put("type", "actions") }
        }

        slack.postEphemeralCard(
            channel = "C1",
            user = "U1",
            text = "Open your household budget",
            blocks = blocks,
        )

        assertEquals(setOf("channel", "user", "text", "blocks"), receivedBody.keys)
        assertEquals("C1", receivedBody.getValue("channel").jsonPrimitive.content)
        assertEquals("U1", receivedBody.getValue("user").jsonPrimitive.content)
        assertEquals(
            "Open your household budget",
            receivedBody.getValue("text").jsonPrimitive.content,
        )
        assertEquals("actions", receivedBody.getValue("blocks").jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Slack API rejection is surfaced to command handling`() = testApplication {
        application {
            configureSerialization()
            routing {
                post("/api/chat.postEphemeral") {
                    call.respond(
                        buildJsonObject {
                            put("ok", false)
                            put("error", "channel_not_found")
                        },
                    )
                }
            }
        }

        val slack = SlackClient(
            http = createClient {
                install(ContentNegotiation) { json() }
            },
            botToken = "xoxb-test",
            apiBaseUrl = "/api",
        )

        val error = assertFailsWith<SlackApiException> {
            slack.postEphemeral("C1", "U1", "Open your budget")
        }

        assertEquals(
            "chat.postEphemeral failed: channel_not_found",
            error.message,
        )
    }
}
