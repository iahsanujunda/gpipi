package me.gpipi.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val json = Json { ignoreUnknownKeys = true }

@Serializable private data class ChatMessage(val content: String? = null)
@Serializable private data class ChatChoice(val message: ChatMessage)
@Serializable private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

class OpenRouterClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String,
) {
    suspend fun chat(userMessage: String, systemPrompt: String, schema: JsonObject): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", systemPrompt) }
                addJsonObject { put("role", "user"); put("content", userMessage) }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "expense_extraction")   // required by the OpenAI json_schema spec; DashScope/Qwen enforces it
                    put("strict", true); put("schema", schema)
                }
            }
            putJsonArray("plugins") { addJsonObject { put("id", "response-healing") } }
            put("temperature", 0)
        }

        val response = try {
            http.post("https://openrouter.ai/api/v1/chat/completions") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            throw AiException("OpenRouter call failed", ex)
        }

        if (!response.status.isSuccess()) {
            throw AiException("OpenRouter ${response.status}: ${response.bodyAsText().take(200)}")
        }

        return json.decodeFromString<ChatResponse>(response.bodyAsText())
            .choices.firstOrNull()?.message?.content
            ?: throw AiException("OpenRouter response had no content")
    }
}
