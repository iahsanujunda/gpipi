package me.gpipi.extraction

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

private val json = Json { ignoreUnknownKeys = true }

private val SYSTEM_PROMPT = """
You extract a single household expense from a short casual message (English, Japanese, or mixed).

Return JSON matching the schema. Rules:
- amount: integer yen. "1500jpy", "¥1,500", "1500円" all → 1500.
- merchant: the shop/place if named (keep original form: "Ito Yokado", "セブン"), else null.
- category: choose exactly one from the list below by best fit.
  - If the message explicitly names a spend type (e.g. "groceries", "lunch", "train", "medicine"),
    that stated intent decides the category — even when the merchant's usual type differs.
    Example: "groceries at seven eleven" → Monthly Groceries (the user said groceries, though
    7-Eleven is normally a convenience store).
  - Use the merchant to decide the category only when no spend type is stated.
    Example: "510 at seven eleven" → Convenience Store.
- confidence: 0-1. Lower it when the merchant is unknown or the category is a guess.
- note: anything the user added that isn't amount/merchant/category, else null.

Categories:
- Eating Out — restaurants, cafes, ramen, izakaya, takeout meals
- Convenience Store — konbini, small quick purchases (Seven, Lawson, FamilyMart)
- Monthly Groceries — supermarket runs, bulk shopping (Ito Yokado, Tokyu Store, OK)
- Transport — trains, buses, taxi, IC top-ups
- Household — daily goods, drugstore, home supplies
- Other — anything that fits nothing above"""

@Serializable private data class ChatMessage(val content: String? = null)
@Serializable private data class ChatChoice(val message: ChatMessage)
@Serializable private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class OpenRouterClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String
) {
    suspend fun extract(slackText: String): Extraction {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) }
                addJsonObject { put("role", "user"); put("content", slackText) }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "expense_extraction")   // required by the OpenAI json_schema spec; DashScope/Qwen enforces it
                    put("strict", true); put("schema", EXTRACTION_SCHEMA)
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
            throw ExtractionException("OpenRouter call failed", ex)
        }

        if (!response.status.isSuccess()) {
            throw ExtractionException("OpenRouter ${response.status}: ${response.bodyAsText().take(200)}")
        }

        val content = json.decodeFromString<ChatResponse>(response.bodyAsText())
            .choices.firstOrNull()?.message?.content
            ?: throw ExtractionException("OpenRouter response had no content")
        return try {
            json.decodeFromString<Extraction>(content)
        } catch (e: SerializationException) {
            throw ExtractionException("Extraction didn't match schema: ${content.take(200)}", e)
        }
    }
}