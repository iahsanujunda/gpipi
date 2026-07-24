package me.gpipi.extraction

import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.gpipi.ai.AiException
import me.gpipi.ai.OpenRouterClient
import me.gpipi.category.ActiveCategoryReader
import me.gpipi.category.CategoryRow

private val json = Json { ignoreUnknownKeys = true }

private const val SYSTEM_PROMPT_TEMPLATE = """
You extract one household expense from a short casual message written in English, Japanese, or a mixture of both.

Return JSON matching the provided schema.

Rules:
- amount: Return integer yen. "1500jpy", "¥1,500", and "1500円" all become 1500.
- currency: Always return "JPY".
- merchant: Return the shop, business, or place when named. Preserve its original form. Otherwise return null.
- category: Choose exactly one category from the supplied list.
  1. Explicit intent has highest priority. If the user names a supplied category or states a purpose
     matching a category description, choose that category even when the merchant or item might
     normally suggest another.
  2. When no explicit purpose is given, infer the category from the merchant, item, and amount.
  3. Use category descriptions as the source of truth. Do not invent a category or return one outside
     the supplied list.
  4. If more than one category could apply, choose the closest match and lower confidence.
- confidence: Return a number from 0 to 1 representing confidence in the complete extraction. Lower it
  when the amount is unclear, the merchant is unknown, or categorization requires guessing.
- note: Return any useful information not already represented by amount, merchant, or category.
  Otherwise return null.

Categories:
{{CATEGORIES}}"""

class ExtractionService(
    private val activeCategories: ActiveCategoryReader,
    private val orClient: OpenRouterClient,
) {
    fun buildSystemPrompt(categories: List<CategoryRow>): String =
        SYSTEM_PROMPT_TEMPLATE.replace(
            "{{CATEGORIES}}",
            categories.joinToString("\n") { "- ${it.name} — ${it.description}" }
        )

    fun buildExtractionSchema(categories: List<CategoryRow>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("amount")     { put("type", "integer") }
            putJsonObject("currency")   { put("type", "string"); putJsonArray("enum") { add("JPY") } }
            putJsonObject("merchant")   { putJsonArray("type") { add("string"); add("null") } }
            putJsonObject("category")   { put("type", "string"); putJsonArray("enum") { categories.forEach { add(it.name) } } }
            putJsonObject("confidence") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
            putJsonObject("note")       { putJsonArray("type") { add("string"); add("null") } }
        }
        putJsonArray("required") { add("amount"); add("currency"); add("category"); add("confidence") }
        put("additionalProperties", false)
    }


    suspend fun extract(text: String): ExtractionResult {
        val categories = activeCategories.current()
        val content = try {
            orClient.chat(text, buildSystemPrompt(categories), buildExtractionSchema(categories))
        } catch (ex: AiException) {
            throw ExtractionException("AI call failed: ${ex.message}", ex)
        }
        val x = try {
            json.decodeFromString<Extraction>(content)
        } catch (ex: SerializationException) {
            throw ExtractionException("Extraction didn't match schema: ${content.take(200)}", ex)
        }
        val categoryId = categories.firstOrNull { it.name == x.category }?.id
            ?: throw ExtractionException("Model returned unknown category '${x.category}'")
        // Hand back the cached category list too — the confirmation card's dropdown needs every
        // active (id, name), and re-fetching would waste the load extract() already did.
        return ExtractionResult(x, categoryId, categories)
    }
}

/**
 * The result of an extraction: the parsed record, the resolved category id, and the active
 * category list used to build the prompt — reused by the confirmation card so it doesn't re-query.
 * Component order keeps `val (x, categoryId) = extract(...)` destructuring working.
 */
data class ExtractionResult(
    val extraction: Extraction,
    val categoryId: UUID,
    val categories: List<CategoryRow>,
)
