package me.gpipi.ai

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.gpipi.configureSerialization

class OpenRouterClientTest {
    @Test
    fun `chat returns the model reported by OpenRouter`() = testApplication {
        lateinit var requestBody: JsonObject
        application {
            configureSerialization()
            routing {
                post("/api/v1/chat/completions") {
                    requestBody = call.receive()
                    call.respond(chatResponse(model = "resolved/model-version"))
                }
            }
        }

        val result = client(model = "requested/model-alias").chat(
            userMessage = "1500 ramen",
            systemPrompt = "extract an expense",
            schema = schema(),
        )

        assertEquals("requested/model-alias", requestBody["model"]!!.jsonPrimitive.content)
        assertEquals(
            true,
            requestBody["provider"]!!.jsonObject["require_parameters"]!!.jsonPrimitive.content.toBoolean(),
        )
        assertEquals("{\"amount\":1500}", result.content)
        assertEquals("resolved/model-version", result.model)
    }

    @Test
    fun `chat rejects a response without a model`() = testApplication {
        application {
            configureSerialization()
            routing {
                post("/api/v1/chat/completions") {
                    call.respond(chatResponse(model = null))
                }
            }
        }

        val error = assertFailsWith<AiException> {
            client().chat("1500 ramen", "extract an expense", schema())
        }

        assertEquals("OpenRouter response had no model", error.message)
    }

    @Test
    fun `chat wraps a malformed response as an AiException`() = testApplication {
        application {
            routing {
                post("/api/v1/chat/completions") {
                    call.respondText("not json")
                }
            }
        }

        val error = assertFailsWith<AiException> {
            client().chat("1500 ramen", "extract an expense", schema())
        }

        assertEquals("OpenRouter response was malformed", error.message)
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.client(
        model: String = "requested/model",
    ) = OpenRouterClient(
        http = createClient {
            install(ContentNegotiation) { json() }
        },
        apiKey = "test-key",
        model = model,
        apiBaseUrl = "/api/v1",
    )

    private fun schema() = buildJsonObject { put("type", "object") }

    private fun chatResponse(model: String?) = buildJsonObject {
        model?.let { put("model", it) }
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("content", "{\"amount\":1500}")
                }
            }
        }
    }
}
